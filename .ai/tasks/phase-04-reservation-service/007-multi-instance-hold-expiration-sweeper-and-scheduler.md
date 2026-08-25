# TASK-P04-007: Multi-Instance Hold Expiration Sweeper & Sweeper Concurrency Tests

## 1. Task Metadata
- **Task ID:** `TASK-P04-007`
- **Git Branch:** `feat/p04-007-multi-instance-hold-expiration-sweeper-and-scheduler`
- **Target Module:** `backend/services/reservation-service`
- **Phase:** `Phase 04 - Reservation & Hold Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 6), `.ai/architecture/03-database-models.md` (Section 2.4), `.ai/architecture/05-messaging-and-outbox.md`
- **Related ADRs:** `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the multi-instance safe background scheduler in `reservation-service` to automatically sweep and release expired 15-minute seat reservation holds. Ensure cluster safety, dead-lock prevention, sub-millisecond query performance via partial indexing, and transactional outbox event generation (`ReservationExpiredEvent`) for downstream realtime and notification updates.

### Critical Invariants to Enforce:
- [ ] **Invariant #2 (15-Minute Seat Hold Sweeper):** Background sweeper continuously scans for holds where `status = 'PENDING' AND expires_at < :now`.
- [ ] **Cluster Safety & Zero Deadlocks (ADR-002):** Sweeper uses `SELECT * FROM reservations WHERE status = 'PENDING' AND expires_at < :now ORDER BY expires_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED` via partial index `idx_res_pending_expires_at`. Multiple running instances across Kubernetes/Cloud Run pods safely process non-overlapping batches without lock contention.
- [ ] **State Machine & Seat Release:** Each expired reservation transitions to `ReservationStatus.EXPIRED`, its associated `SeatHold` records transition to `SeatHoldStatus.RELEASED`, and `updated_at = :now`.
- [ ] **Zero Double-Booking Guarantee After Release:** Once a hold is transitioned to `RELEASED`, the partial unique index `uq_active_seat_hold` on `(event_id, seat_id) WHERE status IN ('HELD', 'SOLD')` immediately allows new customers to reserve those seats.
- [ ] **Transactional Outbox Event:** Commits `ReservationExpiredEvent` wrapped in `EventEnvelope<DomainEvent>` to `outbox_events` within the same transaction that updates the aggregate.
- [ ] **Configurable & Test-Controllable:** Sweeper interval defaults to 10 seconds (`reservation.cleanup.interval-ms: 10000`), batch size defaults to 100 (`reservation.cleanup.batch-size: 100`), and can be disabled via `reservation.cleanup.enabled: false`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/messaging/event/ReservationExpiredEvent.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/scheduler/ReservationExpirationScheduler.java`
- `[MODIFY]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/service/ReservationService.java` — add `expireHoldReservations(Instant now, int batchSize)` method contract.
- `[MODIFY]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/service/impl/ReservationServiceImpl.java` — implement `expireHoldReservations`.
- `[MODIFY]` `backend/services/reservation-service/src/main/resources/application.yaml` — configure sweeper properties.
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/scheduler/ReservationExpirationSchedulerTest.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/integration/ReservationExpirationConcurrencyIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Domain Event Definition
`ReservationExpiredEvent.java` in `com.seatflow.reservation.messaging.event`:

```java
package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event published when a reservation hold exceeds the 15-minute expiration window and is released")
public record ReservationExpiredEvent(
    UUID reservationId,
    UUID eventId,
    List<UUID> seatIds,
    String reason,
    Instant occurredAt
) implements DomainEvent {}
```

---

### 4.2 Service Layer Expiration Method
Add to `ReservationService.java`:
```java
int expireHoldReservations(Instant now, int batchSize);
```

Add to `ReservationServiceImpl.java`:
```java
@Override
@Transactional
public int expireHoldReservations(Instant now, int batchSize) {
    List<Reservation> expiredList = reservationRepository.findExpiredReservationsForUpdate(now, batchSize);
    if (expiredList.isEmpty()) {
        return 0;
    }

    log.info("Processing expired reservations sweep. count={}", expiredList.size());

    for (Reservation reservation : expiredList) {
        reservation.setStatus(ReservationStatus.EXPIRED);
        reservation.setUpdatedAt(now);

        List<UUID> releasedSeatIds = new ArrayList<>();
        for (SeatHold hold : reservation.getSeatHolds()) {
            hold.setStatus(SeatHoldStatus.RELEASED);
            releasedSeatIds.add(hold.getSeatId());
        }

        reservationRepository.save(reservation);

        ReservationExpiredEvent eventPayload = new ReservationExpiredEvent(
                reservation.getId(),
                reservation.getEventId(),
                releasedSeatIds,
                "HOLD_TIMEOUT_EXCEEDED",
                now
        );
        saveOutboxRecord("ReservationExpired", reservation.getId(), eventPayload);

        log.info("Reservation expired and seat holds released. reservationId={}, eventId={}, seatCount={}",
                reservation.getId(), reservation.getEventId(), releasedSeatIds.size());
    }

    meterRegistry.counter("seatflow.reservations.expired.total").increment(expiredList.size());
    return expiredList.size();
}
```

---

### 4.3 Scheduled Sweeper Component Contract
`ReservationExpirationScheduler.java` in `com.seatflow.reservation.scheduler`:

```java
package com.seatflow.reservation.scheduler;

import com.seatflow.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "reservation.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpirationScheduler {

    private final ReservationService reservationService;

    @Value("${reservation.cleanup.batch-size:100}")
    private int batchSize = 100;

    @Scheduled(fixedDelayString = "${reservation.cleanup.interval-ms:10000}")
    public void sweepExpiredReservations() {
        try {
            int expiredCount = reservationService.expireHoldReservations(Instant.now(), batchSize);
            if (expiredCount > 0) {
                log.info("Hold expiration sweeper completed batch. expiredReservationsCount={}", expiredCount);
            }
        } catch (Exception ex) {
            log.error("Unexpected error during reservation hold expiration sweep", ex);
        }
    }
}
```

---

### 4.4 Scheduler Unit Test Contract
`ReservationExpirationSchedulerTest` uses `@ExtendWith(MockitoExtension.class)`:
- Tests that scheduler invokes `expireHoldReservations` with `Instant.now()` and configured batch size.
- Tests that errors in service do not escape the scheduler method or cause unhandled exceptions.

---

### 4.5 Multi-Threaded Concurrency Test Contract
`ReservationExpirationConcurrencyIntegrationTest`:
- Annotated with `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Starts static `PostgreSQLContainer<>("postgres:16-alpine")`.
- Test scenario:
  1. Insert 50 `PENDING` reservations with `expires_at = Instant.now().minus(1, ChronoUnit.MINUTES)` and active `SeatHold` records (`status = HELD`).
  2. Launch 5 concurrent threads executing `reservationService.expireHoldReservations(Instant.now(), 10)` in parallel, simulating a 5-node microservice deployment.
  3. Await completion of all threads.
  4. Assert:
     - Exactly 50 reservations are updated to `EXPIRED` with zero duplicate processing.
     - All associated `SeatHold` records are updated to `RELEASED`.
     - Exactly 50 `ReservationExpired` outbox records are inserted into `outbox_events`.
     - A subsequent reservation hold on the newly released seat UUIDs succeeds immediately without throwing `SEAT_ALREADY_RESERVED`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p04-007-multi-instance-hold-expiration-sweeper-and-scheduler` from `develop`.
2. Implement `ReservationExpiredEvent` record.
3. Add `expireHoldReservations` method to `ReservationService` and `ReservationServiceImpl` calling `findExpiredReservationsForUpdate(now, batchSize)`.
4. Implement `ReservationExpirationScheduler` with `@Scheduled` and conditional property.
5. Update `application.yaml` with sweeper configuration properties.
6. Write Mockito unit test `ReservationExpirationSchedulerTest`.
7. Write Testcontainers multi-threaded concurrency integration test `ReservationExpirationConcurrencyIntegrationTest`.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/reservation-service -Dtest=ReservationExpirationSchedulerTest,ReservationExpirationConcurrencyIntegrationTest
```

- [ ] Sweeper releases holds past 15-minute expiration using `LIMIT :limit FOR UPDATE SKIP LOCKED`.
- [ ] Concurrent sweeper threads process batches without deadlocks or double-expirations.
- [ ] Released seats are immediately available for new reservations.
- [ ] Every expired hold produces a committed `ReservationExpiredEvent` in `outbox_events`.
- [ ] Task file is moved to `.ai/tasks/completed/phase-04-reservation-service/007-multi-instance-hold-expiration-sweeper-and-scheduler.md` when complete.
