# TASK-P04-003: Event Service Client, Authoritative Pricing & Core Reservation Service Layer

## 1. Task Metadata
- **Task ID:** `TASK-P04-003`
- **Git Branch:** `feat/p04-003-event-service-client-and-core-reservation-service`
- **Target Module:** `backend/services/reservation-service`
- **Phase:** `Phase 04 - Reservation & Hold Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 6), `.ai/architecture/03-database-models.md`, `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/06-api-contracts.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`, `.ai/decisions/ADR-003-automated-event-completion-and-lifecycle-reconciliation.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the synchronous `EventClient` for inter-service communication with `event-service` and the core transactional `ReservationService` business logic. This task enforces server-side pricing calculation, the 15-minute seat hold window, idempotency deduplication, zero double-booking concurrency control, outbox event generation, and Micrometer metrics instrumentation.

### Critical Invariants to Enforce:
- [ ] **Authoritative Server-Side Pricing (ADR-003):** The client provides only `eventId` and `seatIds`. `reservation-service` fetches official priced seat maps from `event-service` via `EventClient` and computes `totalAmount` authoritatively on the server; client prices are NEVER trusted or accepted.
- [ ] **Event Lifecycle Guard (ADR-003):** Reservations are rejected with `ValidationException(ErrorCode.INVALID_REQUEST)` if the event is not `PUBLISHED` or if `eventDate <= Instant.now()`.
- [ ] **Invariant #1 (Max 10 Seats per Reservation):** Reject with `ValidationException("Reservation must contain between 1 and 10 seats", ErrorCode.MAX_SEATS_EXCEEDED)` if `seatIds.size() > 10` or `seatIds.isEmpty()`.
- [ ] **Invariant #2 (15-Minute Expiration):** Holds are created with exact expiry `expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES)`.
- [ ] **Invariant #3 (Zero Double-Booking Guarantee):** Pre-check existing active holds (`HELD`, `SOLD`) and catch database `DataIntegrityViolationException` (PostgreSQL `23505` on `uq_active_seat_hold`), translating it into `ConflictException(ErrorCode.SEAT_ALREADY_RESERVED)`.
- [ ] **Idempotency Guarantee:** If a reservation with the given `idempotencyKey` already exists:
  - If `eventId`, `seatIds`, and customer identity match, return the existing `ReservationResponse` (safe replay).
  - If parameters conflict, throw `ConflictException("Idempotency key reused with different parameters", ErrorCode.CONFLICT)`.
- [ ] **ADR-001 (Hybrid Guest/Customer):** If `userId` is present, attach it to `Reservation` and resolve `customerEmail` from `UserContext.getCurrentUserEmail()` if omitted in payload; for unauthenticated guests, ensure `customerEmail` is provided and validate email format.
- [ ] **Server-Side Authorization Guard:** When accessing or cancelling an authenticated user's reservation (`reservation.getUserId() != null`), unauthenticated guests or unauthorized users are rejected with `ResourceNotFoundException(404)`.
- [ ] **Transactional Outbox Pattern:** Every successful hold or cancellation transaction commits an `EventEnvelope<DomainEvent>` (`ReservationHeldEvent` or `ReservationCancelledEvent`) to `outbox_events` within the aggregate transaction. **Never invoke `KafkaTemplate` directly inside business services.**
- [ ] **Resilience:** `EventClient` uses Resilience4j Circuit Breaker and forwards `X-Correlation-Id`. Remote client outage fails fast with `EventClientUnavailableException` (HTTP 503, `ErrorCode.INTERNAL_SERVER_ERROR`).

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/client/EventClient.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/client/impl/EventClientImpl.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/client/dto/EventSeatMapClientResponse.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/client/dto/SeatMapSectionClientDto.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/client/dto/SeatMapSeatClientDto.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/client/dto/PricingTierClientDto.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/client/dto/EventPricingDetails.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/client/exception/EventClientUnavailableException.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/messaging/event/ReservationHeldEvent.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/messaging/event/ReservationCancelledEvent.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/service/ReservationService.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/service/impl/ReservationServiceImpl.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/client/EventClientTest.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/service/ReservationServiceImplTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Event Client DTOs and Contracts

```java
package com.seatflow.reservation.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventSeatMapClientResponse(
    UUID eventId,
    UUID venueId,
    String eventTitle,
    Instant eventDate,
    String venueName,
    Integer venueCapacity,
    Long totalConfiguredSeats,
    List<SeatMapSectionClientDto> sections
) {}

public record SeatMapSectionClientDto(
    UUID sectionId,
    String name,
    Integer rowCount,
    Integer colCount,
    List<SeatMapSeatClientDto> seats,
    List<PricingTierClientDto> pricingTiers
) {}

public record SeatMapSeatClientDto(
    UUID seatId,
    String rowLabel,
    Integer seatNumber,
    Integer gridX,
    Integer gridY,
    Boolean isActive
) {}

public record PricingTierClientDto(
    UUID id,
    UUID sectionId,
    String categoryName,
    BigDecimal price,
    String currency
) {}

public record EventPricingDetails(
    UUID eventId,
    Instant eventDate,
    java.util.Map<UUID, BigDecimal> seatPrices
) {}
```

#### `EventClient.java`
```java
package com.seatflow.reservation.client;

import com.seatflow.reservation.client.dto.EventPricingDetails;

import java.util.Set;
import java.util.UUID;

public interface EventClient {
    EventPricingDetails getEventSeatPricing(UUID eventId, Set<UUID> requestedSeatIds);
}
```

#### `EventClientImpl.java`
```java
package com.seatflow.reservation.client.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.*;
import com.seatflow.reservation.client.exception.EventClientUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventClientImpl implements EventClient {

    private final RestClient eventRestClient;

    @Override
    @CircuitBreaker(name = "eventService", fallbackMethod = "getEventSeatPricingFallback")
    public EventPricingDetails getEventSeatPricing(UUID eventId, Set<UUID> requestedSeatIds) {
        log.debug("Fetching priced seat map from event-service for eventId={}", eventId);

        EventSeatMapClientResponse response = eventRestClient.get()
                .uri("/api/events/{eventId}/seat-map", eventId)
                .header("X-Correlation-Id", CorrelationContext.getCorrelationId().orElse(""))
                .retrieve()
                .onStatus(status -> status.value() == 404, (req, res) -> {
                    throw new ResourceNotFoundException("Event", eventId);
                })
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new EventClientUnavailableException("Failed to retrieve event details from event-service. Status: " + res.getStatusCode());
                })
                .body(EventSeatMapClientResponse.class);

        if (response == null) {
            throw new EventClientUnavailableException("Empty response from event-service for eventId=" + eventId);
        }

        // Validate event is in future (ADR-003)
        if (response.eventDate() == null || !response.eventDate().isAfter(Instant.now())) {
            throw new ValidationException("Cannot reserve seats for an event in the past", ErrorCode.INVALID_REQUEST);
        }

        // Map requested seat IDs to authoritative section pricing
        Map<UUID, BigDecimal> seatPrices = new HashMap<>();
        if (response.sections() != null) {
            for (SeatMapSectionClientDto section : response.sections()) {
                BigDecimal sectionPrice = (section.pricingTiers() != null && !section.pricingTiers().isEmpty())
                        ? section.pricingTiers().getFirst().price()
                        : BigDecimal.ZERO;

                if (section.seats() != null) {
                    for (SeatMapSeatClientDto seat : section.seats()) {
                        if (requestedSeatIds.contains(seat.seatId())) {
                            if (!Boolean.TRUE.equals(seat.isActive())) {
                                throw new ValidationException("Seat " + seat.seatId() + " is inactive and cannot be reserved", ErrorCode.INVALID_REQUEST);
                            }
                            seatPrices.put(seat.seatId(), sectionPrice);
                        }
                    }
                }
            }
        }

        // Ensure all requested seats were found in venue layout
        for (UUID requestedSeatId : requestedSeatIds) {
            if (!seatPrices.containsKey(requestedSeatId)) {
                throw new ValidationException("Seat " + requestedSeatId + " does not exist in event seat map", ErrorCode.INVALID_REQUEST);
            }
        }

        return new EventPricingDetails(response.eventId(), response.eventDate(), seatPrices);
    }

    public EventPricingDetails getEventSeatPricingFallback(UUID eventId, Set<UUID> requestedSeatIds, Throwable t) {
        if (t instanceof ResourceNotFoundException rne) throw rne;
        if (t instanceof ValidationException ve) throw ve;
        log.error("Circuit breaker triggered for event-service call on eventId={}", eventId, t);
        throw new EventClientUnavailableException("Event catalog service is temporarily unavailable", t);
    }
}
```

---

### 4.2 Domain Events Contract
Create domain event records in `com.seatflow.reservation.messaging.event`:

```java
package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event published when a reservation hold is successfully acquired")
public record ReservationHeldEvent(
    UUID reservationId,
    UUID eventId,
    UUID userId,
    String customerEmail,
    String customerName,
    List<UUID> seatIds,
    Instant expiresAt,
    BigDecimal totalAmount,
    Instant occurredAt
) implements DomainEvent {}

@Schema(description = "Event published when an active reservation hold is cancelled")
public record ReservationCancelledEvent(
    UUID reservationId,
    UUID eventId,
    List<UUID> seatIds,
    String reason,
    Instant occurredAt
) implements DomainEvent {}
```

---

### 4.3 Service Interface & Implementation Contract

```java
package com.seatflow.reservation.service;

import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatAvailabilityResponse;

import java.util.UUID;

public interface ReservationService {

    ReservationResponse createReservation(CreateReservationRequest request, UUID authenticatedUserId);

    ReservationResponse getReservationById(UUID reservationId, UUID authenticatedUserId, boolean isAdmin);

    void cancelReservation(UUID reservationId, UUID authenticatedUserId, boolean isAdmin);

    SeatAvailabilityResponse getSeatAvailability(UUID eventId);
}
```

#### `ReservationServiceImpl.java` Detailed Logic:
```java
package com.seatflow.reservation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.mapper.ReservationMapper;
import com.seatflow.reservation.messaging.event.ReservationCancelledEvent;
import com.seatflow.reservation.messaging.event.ReservationHeldEvent;
import com.seatflow.reservation.model.entity.OutboxEvent;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.repository.projection.ActiveSeatHoldProjection;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.EventSeatStatusResponse;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatAvailabilityResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceImpl implements ReservationService {

    private static final int MAX_SEAT_LIMIT = 10;
    private static final int HOLD_DURATION_MINUTES = 15;

    private final ReservationRepository reservationRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final EventClient eventClient;
    private final ReservationMapper reservationMapper;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request, UUID authenticatedUserId) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            log.info("Processing seat hold request. eventId={}, seatCount={}, authenticatedUserId={}, idempotencyKey={}",
                    request.eventId(), request.seatIds().size(), authenticatedUserId, request.idempotencyKey());

            // 1. Invariant #1: Validate seat limit
            if (request.seatIds() == null || request.seatIds().isEmpty() || request.seatIds().size() > MAX_SEAT_LIMIT) {
                throw new ValidationException("Reservation must contain between 1 and 10 seats", ErrorCode.MAX_SEATS_EXCEEDED);
            }

            // 2. Resolve customer identity (ADR-001)
            String customerEmail = request.customerEmail();
            if (customerEmail == null || customerEmail.isBlank()) {
                customerEmail = UserContext.getCurrentUserEmail().orElse(null);
            }
            if (customerEmail == null || customerEmail.isBlank()) {
                throw new ValidationException("Customer email is required for checkout", ErrorCode.INVALID_REQUEST);
            }

            // 3. Idempotency Check
            Optional<Reservation> existingOpt = reservationRepository.findWithSeatHoldsByIdempotencyKey(request.idempotencyKey());
            if (existingOpt.isPresent()) {
                Reservation existing = existingOpt.get();
                Set<UUID> existingSeatIds = existing.getSeatHolds().stream().map(SeatHold::getSeatId).collect(Collectors.toSet());
                if (existing.getEventId().equals(request.eventId()) && existingSeatIds.equals(request.seatIds())) {
                    log.info("Idempotent replay for reservationId={}, idempotencyKey={}", existing.getId(), request.idempotencyKey());
                    return reservationMapper.toResponse(existing);
                } else {
                    throw new ConflictException("Idempotency key reused with different parameters", ErrorCode.CONFLICT);
                }
            }

            // 4. Concurrency Pre-check: Check if any requested seat is currently HELD or SOLD
            List<SeatHold> conflictingHolds = seatHoldRepository.findByEventIdAndSeatIdInAndStatusIn(
                    request.eventId(),
                    request.seatIds(),
                    List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD)
            );
            if (!conflictingHolds.isEmpty()) {
                Set<UUID> heldSeatIds = conflictingHolds.stream().map(SeatHold::getSeatId).collect(Collectors.toSet());
                log.warn("Seat hold collision. eventId={}, conflictingSeatIds={}", request.eventId(), heldSeatIds);
                throw new ConflictException("One or more selected seats are no longer available: " + heldSeatIds, ErrorCode.SEAT_ALREADY_RESERVED);
            }

            // 5. Authoritative Pricing & Event Guard via EventClient (ADR-003)
            EventPricingDetails pricingDetails = eventClient.getEventSeatPricing(request.eventId(), request.seatIds());

            // 6. Calculate total authoritative amount and build aggregate
            BigDecimal totalAmount = BigDecimal.ZERO;
            Instant now = Instant.now();
            Instant expiresAt = now.plus(HOLD_DURATION_MINUTES, ChronoUnit.MINUTES);

            Reservation reservation = Reservation.builder()
                    .userId(authenticatedUserId)
                    .customerEmail(customerEmail)
                    .customerName(request.customerName())
                    .eventId(request.eventId())
                    .status(ReservationStatus.PENDING)
                    .expiresAt(expiresAt)
                    .idempotencyKey(request.idempotencyKey())
                    .seatCount(request.seatIds().size())
                    .totalAmount(BigDecimal.ZERO)
                    .seatHolds(new ArrayList<>())
                    .build();

            for (UUID seatId : request.seatIds()) {
                BigDecimal seatPrice = pricingDetails.seatPrices().getOrDefault(seatId, BigDecimal.ZERO);
                totalAmount = totalAmount.add(seatPrice);

                SeatHold seatHold = SeatHold.builder()
                        .reservation(reservation)
                        .eventId(request.eventId())
                        .seatId(seatId)
                        .status(SeatHoldStatus.HELD)
                        .price(seatPrice)
                        .build();
                reservation.addSeatHold(seatHold);
            }
            reservation.setTotalAmount(totalAmount);

            // 7. Persist Reservation and SeatHolds (Guaranteed by uq_active_seat_hold partial unique index)
            Reservation savedReservation = reservationRepository.saveAndFlush(reservation);

            // 8. Commit Transactional Outbox Event
            ReservationHeldEvent eventPayload = new ReservationHeldEvent(
                    savedReservation.getId(),
                    savedReservation.getEventId(),
                    savedReservation.getUserId(),
                    savedReservation.getCustomerEmail(),
                    savedReservation.getCustomerName(),
                    new ArrayList<>(request.seatIds()),
                    savedReservation.getExpiresAt(),
                    savedReservation.getTotalAmount(),
                    now
            );
            saveOutboxRecord("ReservationHeld", savedReservation.getId(), eventPayload);

            // Record Prometheus metrics
            meterRegistry.counter("seatflow.reservations.created.total", "eventId", request.eventId().toString(), "status", "SUCCESS").increment();
            log.info("Seat hold acquired successfully. reservationId={}, eventId={}, seatCount={}, totalAmount={}, expiresAt={}",
                    savedReservation.getId(), savedReservation.getEventId(), savedReservation.getSeatCount(), savedReservation.getTotalAmount(), savedReservation.getExpiresAt());

            return reservationMapper.toResponse(savedReservation);

        } catch (DataIntegrityViolationException dive) {
            log.warn("Database unique constraint violation during seat hold. eventId={}, seatIds={}", request.eventId(), request.seatIds(), dive);
            meterRegistry.counter("seatflow.reservations.conflicts.total", "eventId", request.eventId().toString(), "reason", "DB_UNIQUE_VIOLATION").increment();
            throw new ConflictException("One or more selected seats have already been reserved by another customer", ErrorCode.SEAT_ALREADY_RESERVED);
        } finally {
            timer.stop(meterRegistry.timer("seatflow.reservations.hold.duration", "eventId", request.eventId().toString()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getReservationById(UUID reservationId, UUID authenticatedUserId, boolean isAdmin) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (!isAdmin && reservation.getUserId() != null) {
            if (authenticatedUserId == null || !reservation.getUserId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Reservation", reservationId);
            }
        }

        return reservationMapper.toResponse(reservation);
    }

    @Override
    @Transactional
    public void cancelReservation(UUID reservationId, UUID authenticatedUserId, boolean isAdmin) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (!isAdmin && reservation.getUserId() != null) {
            if (authenticatedUserId == null || !reservation.getUserId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Reservation", reservationId);
            }
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ValidationException("Cannot cancel reservation with status: " + reservation.getStatus(), ErrorCode.INVALID_REQUEST);
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        for (SeatHold hold : reservation.getSeatHolds()) {
            hold.setStatus(SeatHoldStatus.RELEASED);
        }
        reservationRepository.save(reservation);

        List<UUID> seatIds = reservation.getSeatHolds().stream().map(SeatHold::getSeatId).toList();
        ReservationCancelledEvent eventPayload = new ReservationCancelledEvent(
                reservation.getId(),
                reservation.getEventId(),
                seatIds,
                "USER_CANCELLED",
                Instant.now()
        );
        saveOutboxRecord("ReservationCancelled", reservation.getId(), eventPayload);

        log.info("Reservation cancelled. reservationId={}, eventId={}, seatCount={}", reservation.getId(), reservation.getEventId(), seatIds.size());
    }

    @Override
    @Transactional(readOnly = true)
    public SeatAvailabilityResponse getSeatAvailability(UUID eventId) {
        List<ActiveSeatHoldProjection> activeHolds = seatHoldRepository.findActiveSeatHoldsByEventId(eventId);
        List<EventSeatStatusResponse> seatStatuses = activeHolds.stream()
                .map(hold -> new EventSeatStatusResponse(hold.getSeatId(), hold.getStatus()))
                .toList();
        return new SeatAvailabilityResponse(eventId, seatStatuses);
    }

    private void saveOutboxRecord(String eventType, UUID aggregateId, Object payload) {
        try {
            EventEnvelope<?> envelope = EventEnvelope.of(
                    eventType,
                    aggregateId.toString(),
                    CorrelationContext.getCorrelationId().orElse(UUID.randomUUID().toString()),
                    (com.seatflow.common.events.DomainEvent) payload
            );
            String payloadJson = objectMapper.writeValueAsString(envelope);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payloadJson)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            log.error("Failed to serialize outbox event payload. aggregateId={}, eventType={}", aggregateId, eventType, ex);
            throw new RuntimeException("Failed to commit outbox event", ex);
        }
    }
}
```

---

### 4.4 Unit Tests Contract
- `EventClientTest`: Mockito unit tests verifying:
  - Successful seat map retrieval and seat-to-price mapping.
  - Rejection of past events with `ValidationException`.
  - Rejection of unknown seat UUIDs in layout with `ValidationException`.
  - Circuit breaker fallback triggering `EventClientUnavailableException`.
- `ReservationServiceImplTest`: Mockito unit tests verifying:
  - Rejection of >10 seats with `ValidationException(ErrorCode.MAX_SEATS_EXCEEDED)`.
  - Email resolution from `UserContext` for authenticated users when payload email is null.
  - Authorization protection on `getReservationById` and `cancelReservation` rejecting unauthenticated or mismatched users.
  - Exact 15-minute `expiresAt` calculation.
  - Idempotent replay on matching key and error on mismatched parameters.
  - Pre-check conflict throwing `ConflictException(ErrorCode.SEAT_ALREADY_RESERVED)`.
  - Handling of `DataIntegrityViolationException` mapping to `ConflictException`.
  - Outbox event serialization and persistence for `ReservationHeld` and `ReservationCancelled`.
  - Cancellation of `PENDING` hold releasing seats to `RELEASED`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p04-003-event-service-client-and-core-reservation-service` from `develop`.
2. Implement `EventClient` interface, DTOs, `EventClientUnavailableException`, and `EventClientImpl` with Resilience4j circuit breaker.
3. Implement `ReservationHeldEvent` and `ReservationCancelledEvent` implementing `DomainEvent`.
4. Implement `ReservationService` and `ReservationServiceImpl` with all business invariants, server-side pricing, email claim resolution, authorization checks, idempotency, and outbox persistence.
5. Write Mockito unit tests `EventClientTest` and `ReservationServiceImplTest`.
6. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/reservation-service -Dtest=EventClientTest,ReservationServiceImplTest
```

- [ ] Max 10 seats, 15-min expiration, and authoritative pricing calculations are strictly enforced.
- [ ] Concurrency collisions and database integrity violations return `ConflictException(ErrorCode.SEAT_ALREADY_RESERVED)`.
- [ ] Authenticated email is resolved from token context and authorization guards prevent guest access to user reservations.
- [ ] No direct Kafka template calls occur in service transactions; all domain events write to `outbox_events`.
- [ ] Task file is moved to `.ai/tasks/completed/phase-04-reservation-service/003-event-service-client-and-core-reservation-service.md` when complete.
