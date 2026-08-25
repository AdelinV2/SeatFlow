# TASK-P03-003: Event Lifecycle and Pricing Service Layer

## 1. Task Metadata
- **Task ID:** `TASK-P03-003`
- **Git Branch:** `feat/p03-003-event-and-pricing-service-layer`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 03 - Event Catalog Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 5), `.ai/architecture/03-database-models.md`, `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/06-api-contracts.md`
- **Related ADRs:** `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement transactional event lifecycle and pricing services. Every state mutation must validate the venue/pricing invariants and persist its corresponding domain event into the same database transaction as the aggregate mutation. This task creates a port for venue validation; Task 004 supplies its HTTP adapter.

### Critical Invariants to Enforce:
- [ ] New events always begin as `DRAFT`; callers cannot create a published event.
- [ ] Only `DRAFT -> PUBLISHED`, `DRAFT -> CANCELLED`, `PUBLISHED -> CANCELLED`, and `PUBLISHED -> COMPLETED` transitions are legal. No status may move back to `DRAFT` or out of `CANCELLED`/`COMPLETED`.
- [ ] An event may be published only when it has one or more pricing tiers and the referenced venue exists.
- [ ] Pricing may be configured only for `DRAFT` or `PUBLISHED` events; `CANCELLED` and `COMPLETED` events are immutable.
- [ ] Each request’s `(sectionId, categoryName)` pairs must be unique; every section must belong to the event’s venue; all tiers in a bulk replacement must use one currency.
- [ ] An event’s venue must exist on create and before publication. A remote validation outage fails closed with `BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)`; a missing venue is `ValidationException(ErrorCode.INVALID_REQUEST)`.
- [ ] No raw `db commit -> Kafka publish` occurs. `EventCreated`, `EventPublished`, and `EventCancelled` envelopes are serialized into `OutboxEvent.payload` in the aggregate transaction.
- [ ] Entities are never returned from service interfaces; use common `PagedResult` and response records.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/client/VenueValidationPort.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/messaging/event/EventCreatedEvent.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/messaging/event/EventPublishedEvent.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/messaging/event/EventCancelledEvent.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/service/EventService.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/service/EventPricingService.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/service/impl/EventServiceImpl.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/service/impl/EventPricingServiceImpl.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/model/common/EventPriceRange.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/service/EventServiceImplTest.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/service/EventPricingServiceImplTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Venue Port and Domain Event Records
The port isolates Task 003 from the HTTP implementation in Task 004:

```java
public interface VenueValidationPort {
    boolean venueExists(UUID venueId);
    boolean sectionBelongsToVenue(UUID venueId, UUID sectionId);
}
```

Each local event implements `com.seatflow.common.events.DomainEvent`, has `@Schema` descriptions, and is immutable:

```java
public record EventCreatedEvent(
    UUID eventId, UUID venueId, String title, EventCategory category, Instant eventDate, Instant occurredAt
) implements DomainEvent {}

public record EventPublishedEvent(
    UUID eventId, UUID venueId, String title, EventCategory category, Instant eventDate, Instant occurredAt
) implements DomainEvent {}

public record EventCancelledEvent(
    UUID eventId, UUID venueId, String title, Instant eventDate, Instant occurredAt
) implements DomainEvent {}
```

For every persisted local event, construct `EventEnvelope<DomainEvent>` via `EventEnvelope.of(eventType, aggregateId.toString(), correlationId, payload)`. Serialize the envelope to JSON String with `objectMapper.writeValueAsString(envelope)` and persist it in `OutboxEvent` with `payload(payloadJson)` before transaction commit.

### 4.2 Service Interface Contracts
```java
public interface EventService {
    EventDetailResponse createEvent(CreateEventRequest request);
    EventDetailResponse updateEvent(UUID eventId, UpdateEventRequest request);
    PagedResult<EventSummaryResponse> findPublishedEvents(EventCategory category, String search, Pageable pageable);
    EventDetailResponse getPublishedEvent(UUID eventId);
    EventDetailResponse getEventForAdministration(UUID eventId);
    EventSeatMapResponse getEventSeatMap(UUID eventId);
}

public interface EventPricingService {
    List<PricingTierResponse> configurePricing(UUID eventId, ConfigurePricingRequest request);
    List<PricingTierResponse> getPricingTiers(UUID eventId);
    EventPriceRange getPriceRange(UUID eventId);
}

public record EventPriceRange(BigDecimal minPrice, BigDecimal maxPrice, String currency) {}
```

`findPublishedEvents` returns a `PagedResult<EventSummaryResponse>`. It queries published future events via `EventRepository.findAll(Specification, Pageable)`. To prevent N+1 queries, it collects all event IDs in the page and queries price ranges in a single batch via `EventPricingTierRepository.findPriceRangesByEventIds(eventIds)`. It then maps each event to `EventSummaryResponse` using `eventMapper.toSummaryResponse(event, minPrice, maxPrice, currency)` and returns `PagedResult.of(...)`. A missing event is `ResourceNotFoundException`; public lookup of a non-published event behaves as not found so draft metadata is not leaked.

### 4.3 Transactional Lifecycle Rules
`EventServiceImpl.createEvent` is `@Transactional`: validate `venueValidationPort.venueExists(request.venueId())`, map the request, explicitly set DRAFT and timestamps, save, create `EventCreatedEvent`, save the outbox record, and return a mapped detail response with an empty pricing list.

`updateEvent` is `@Transactional`: reject an update where every component is null; load the aggregate with its pricing tiers; apply only non-null metadata values. When `status` differs from current status, apply this state table:

| Current | Requested | Result |
|---|---|---|
| DRAFT | PUBLISHED | validate venue and at least one pricing tier; save `EventPublished` outbox record |
| DRAFT | CANCELLED | save `EventCancelled` outbox record |
| PUBLISHED | CANCELLED | save `EventCancelled` outbox record |
| PUBLISHED | COMPLETED | persist state only |
| Any other pair or same terminal state | any change | throw `ValidationException(ErrorCode.INVALID_REQUEST)` |

Metadata may be edited while DRAFT or PUBLISHED, but any attempted metadata/status change after cancellation/completion is rejected. Explicitly set `updatedAt = Instant.now()` for each accepted mutation. Optimistic lock failures are translated by the common handler to conflict semantics; do not introduce service-local exception advice.

`getEventSeatMap` is `@Transactional(readOnly = true)`: load the published event (throwing `ResourceNotFoundException` if missing or not `PUBLISHED`), retrieve the venue layout via `SeatMapClient` (extending `VenueValidationPort`), load the event's pricing tiers, map each venue section to `SeatMapSectionResponse` containing only its matching pricing tiers, calculate `totalConfiguredSeats` from the section seats if null from upstream, and return `EventSeatMapResponse`.

### 4.4 Pricing Rules
`EventPricingServiceImpl.configurePricing` is `@Transactional` and treats the request as authoritative bulk replacement. Load the event with its existing tiers, reject terminal statuses, reject duplicate `(sectionId, categoryName)` pairs, require a single currency, call `sectionBelongsToVenue(event.venueId, sectionId)` for every tier, clear existing tiers/orphan rows, map each validated item, associate it with the event, update the timestamp, save/flush, and return price-ascending responses. A false section result is `ValidationException(ErrorCode.INVALID_REQUEST)`; a port outage is `BusinessException(ErrorCode.INTERNAL_SERVER_ERROR)`. `getPriceRange` calls `findPriceRangeByEventId(eventId)`: if projection is null or `getMinPrice() == null`, it returns `new EventPriceRange(null, null, null)`, otherwise returning the min, max, and currency.

### 4.5 Unit Test Contracts
Both test classes use `@ExtendWith(MockitoExtension.class)` and mock repositories, mappers, `ObjectMapper`, `VenueValidationPort`/`SeatMapClient`, and `CorrelationContext` access where applicable. `EventServiceImplTest` must prove: draft creation and `EventCreated` outbox persistence; missing venue rejection; allowed publish transition and `EventPublished`; publish-without-pricing rejection; cancellation and `EventCancelled`; illegal/reverse/terminal transitions; public lookup hides draft events; empty update rejection; pagination mapping with batch price lookup; seat map composition with overlaid section prices. `EventPricingServiceImplTest` must prove: valid replacement; duplicate tier rejection; mixed currency rejection; foreign section rejection; terminal event rejection; empty range handling null SQL aggregates; and correct min/max calculation. Assert no KafkaTemplate is injected or invoked in either test.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p03-003-event-and-pricing-service-layer` from `develop`.
2. Add the venue-validation port, immutable local events, and the price-range value record.
3. Implement the service interfaces and transactional event lifecycle including validated state transitions.
4. Implement atomic tier replacement and price-range calculation.
5. Serialize and persist every required envelope through the outbox repository inside its domain transaction.
6. Add the prescribed Mockito unit tests, including all negative/invariant paths.
7. Run the targeted test command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/event-service -Dtest=EventServiceImplTest,EventPricingServiceImplTest
```

- [ ] All allowed and forbidden state transitions are covered.
- [ ] Publish cannot occur without tiers and venue validation.
- [ ] Pricing is atomic, price-safe, and locked after cancellation/completion.
- [ ] All three lifecycle events are durable outbox records, never direct Kafka sends.
- [ ] Task file is moved to `.ai/tasks/completed/phase-03-event-service/003-event-and-pricing-service-layer.md` when complete.
