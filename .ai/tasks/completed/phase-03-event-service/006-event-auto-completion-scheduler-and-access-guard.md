# TASK-P03-006: Event Lifecycle Auto-Completion Scheduler, Access Guard & Outbox Event

## 1. Task Metadata
- **Task ID:** `TASK-P03-006`
- **Git Branch:** `feat/p03-006-event-auto-completion-scheduler-and-access-guard`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 03 - Event Catalog Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md`, `.ai/architecture/03-database-models.md`, `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/06-api-contracts.md`
- **Related ADRs:** `.ai/decisions/ADR-003-automated-event-completion-and-lifecycle-reconciliation.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the fail-fast public access guard and scheduled lifecycle auto-completion sweeper in `event-service`. Ensure that past events are never returned by public endpoints or sold, and that PostgreSQL event states are periodically reconciled to `COMPLETED` while emitting transactional `EventCompletedEvent` records into the outbox.

### Critical Invariants to Enforce:
- [ ] Public endpoints `GET /api/events/{eventId}` and `GET /api/events/{eventId}/seat-map` must return 404 (`ResourceNotFoundException`) if `eventDate <= Instant.now()`, even if `status == PUBLISHED`.
- [ ] Administrative endpoints (`GET /api/admin/events/{eventId}`, `PUT /api/admin/events/{eventId}`) retain visibility and management over past and completed events.
- [ ] The `EventCompletionScheduler` sweeps only `status = 'PUBLISHED' AND event_date <= :now` using batch-limited, lock-safe queries (`FOR UPDATE SKIP LOCKED` or chunked atomic updates) across multiple instances.
- [ ] Every auto-completed event transitions to `EventStatus.COMPLETED`, sets `updated_at = Instant.now()`, and commits an `EVENT_COMPLETED` envelope to `outbox_events` in the same database transaction.
- [ ] No direct Kafka sends from the scheduler; all events route through the durable transactional outbox table.
- [ ] Background scheduler is configurable via `event.completion.cron` and can be disabled in test profiles via `event.completion.enabled=false`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/messaging/event/EventCompletedEvent.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/scheduler/EventCompletionScheduler.java`
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/repository/EventRepository.java` — add `findPublishedExpiredForUpdate(Instant now, Pageable pageable)` query.
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/service/EventService.java` — add `completeExpiredEvents(Instant now, int batchSize)` method contract.
- `[MODIFY]` `backend/services/event-service/src/main/java/com/seatflow/event/service/impl/EventServiceImpl.java` — implement `completeExpiredEvents` and add `eventDate > Instant.now()` guards to `getPublishedEvent` & `getEventSeatMap`.
- `[MODIFY]` `backend/services/event-service/src/main/resources/application.yaml` — configure scheduler defaults (`cron: "0 */15 * * * *"`, `enabled: true`, `batch-size: 50`).
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/scheduler/EventCompletionSchedulerTest.java`
- `[MODIFY]` `backend/services/event-service/src/test/java/com/seatflow/event/service/EventServiceImplTest.java` — test access guards and completion logic.

---

## 4. Technical Specifications & Contracts

### 4.1 Domain Event Definition
Create `EventCompletedEvent` in `com.seatflow.event.messaging.event`:

```java
package com.seatflow.event.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Domain event published when an event lifecycle completes")
public record EventCompletedEvent(
    @Schema(description = "Event unique identifier") UUID eventId,
    @Schema(description = "Associated venue identifier") UUID venueId,
    @Schema(description = "Event title") String title,
    @Schema(description = "Scheduled start time") Instant eventDate,
    @Schema(description = "Completion timestamp") Instant occurredAt
) implements DomainEvent {}
```

### 4.2 Repository Query Contract
In `EventRepository.java`:

```java
@Query(value = "SELECT e FROM Event e WHERE e.status = 'PUBLISHED' AND e.eventDate <= :now ORDER BY e.eventDate ASC")
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")}) // SKIP LOCKED
List<Event> findPublishedExpiredForUpdate(@Param("now") Instant now, Pageable pageable);
```

### 4.3 Service Access Guards & Completion Method
In `EventServiceImpl`:

1. **Guarding Public Detail & Seat Map:**
   ```java
   @Override
   @Transactional(readOnly = true)
   public EventDetailResponse getPublishedEvent(UUID eventId) {
       Event event = eventRepository.findWithPricingTiersById(eventId)
               .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
       if (event.getStatus() != EventStatus.PUBLISHED || !event.getEventDate().isAfter(Instant.now())) {
           throw new ResourceNotFoundException("Event", eventId);
       }
       return eventMapper.toDetailResponse(event);
   }

   @Override
   @Transactional(readOnly = true)
   public EventSeatMapResponse getEventSeatMap(UUID eventId) {
       Event event = eventRepository.findWithPricingTiersById(eventId)
               .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
       if (event.getStatus() != EventStatus.PUBLISHED || !event.getEventDate().isAfter(Instant.now())) {
           throw new ResourceNotFoundException("Event", eventId);
       }
       // ... existing seat map assembly
   }
   ```

2. **Completing Expired Events:**
   ```java
   @Override
   @Transactional
   public int completeExpiredEvents(Instant now, int batchSize) {
       List<Event> expired = eventRepository.findPublishedExpiredForUpdate(now, PageRequest.of(0, batchSize));
       for (Event event : expired) {
           event.setStatus(EventStatus.COMPLETED);
           event.setUpdatedAt(now);
           publishOutbox(EVENT_COMPLETED, event.getId(),
                   new EventCompletedEvent(event.getId(), event.getVenueId(), event.getTitle(),
                           event.getEventDate(), now));
       }
       return expired.size();
   }
   ```

### 4.4 Scheduler Component Contract
In `EventCompletionScheduler.java`:

```java
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "event.completion.enabled", havingValue = "true", matchIfMissing = true)
public class EventCompletionScheduler {

    private final EventService eventService;

    @Value("${event.completion.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(cron = "${event.completion.cron:0 */15 * * * *}")
    public void sweepExpiredEvents() {
        try {
            int completed = eventService.completeExpiredEvents(Instant.now(), batchSize);
            if (completed > 0) {
                log.info("Auto-completed expired events. count={}", completed);
            }
        } catch (Exception ex) {
            log.error("Error during event completion sweep", ex);
        }
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p03-006-event-auto-completion-scheduler-and-access-guard` from `develop`.
2. Create `EventCompletedEvent` record implementing `DomainEvent`.
3. Add `findPublishedExpiredForUpdate` to `EventRepository` with pessimistic write lock and `SKIP LOCKED`.
4. Update `EventServiceImpl` with `eventDate > Instant.now()` guards on public detail/seat-map queries.
5. Implement `completeExpiredEvents` in `EventServiceImpl` with transactional state update and outbox insertion.
6. Create `EventCompletionScheduler` with conditional activation and cron configuration.
7. Update unit tests in `EventServiceImplTest` and add `EventCompletionSchedulerTest`.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/event-service -Dtest=EventServiceImplTest,EventCompletionSchedulerTest
```

- [ ] Public endpoints reject past events with 404.
- [ ] Admin endpoints allow retrieving past and completed events.
- [ ] Expired published events are transitioned to `COMPLETED` with `EVENT_COMPLETED` outbox record.
- [ ] Scheduler handles multi-instance concurrency safely via `SKIP LOCKED`.
- [ ] Task file is moved to `.ai/tasks/completed/phase-03-event-service/006-event-auto-completion-scheduler-and-access-guard.md` when complete.
