# 05 — Messaging & Transactional Outbox Specification

SeatFlow uses **Apache Kafka** as its asynchronous backbone for decoupled event-driven workflows, combined with the **Transactional Outbox Pattern** to eliminate dual-write hazards.

---

## 1. Kafka Cluster & Topic Catalog

All Kafka topics are partitioned and keyed by their primary aggregate identifier (e.g. `reservationId`, `eventId`, `paymentId`) to guarantee in-order delivery per entity.

| Topic Name | Producer(s) | Consumer(s) | Key | Purpose |
|---|---|---|---|---|
| `seatflow.user.events` | User Service | Ticket, Reservation | `userId` | User registered/first login (auto-claims historical guest tickets) |
| `seatflow.reservation.events` | Reservation Service | Realtime, Notification | `reservationId` | Reservation held, expired, confirmed, or cancelled |
| `seatflow.payment.events` | Payment Service | Ticket, Notification, Reservation | `paymentId` | Payment completed, failed, refunded |
| `seatflow.ticket.events` | Ticket Service | Notification | `ticketId` | Ticket generated and issued |
| `seatflow.seatmap.events` | Seat Map Service | Event Service | `venueId` / `sectionId` | Venue created, section and seat grid created |
| `seatflow.event.events` | Event Service | Downstream Services | `eventId` | Event created, published, or cancelled |
| `seatflow.notification.events` | Multiple Services | Notification Service | `notificationId` | Internal notification triggers |

*Note: The Realtime Service subscribes directly to `seatflow.reservation.events` and `seatflow.payment.events` lifecycle topics to broadcast WebSocket seat status updates.*

---

## 2. Event Envelope & Domain Event Payloads

### 2.1 Universal Event Envelope (`EventEnvelope<T>`)
Every Kafka message must be serialized as an `EventEnvelope<T>` from `common-events`:
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "ReservationHeld",
  "occurredAt": "2026-08-23T14:30:00Z",
  "correlationId": "corr-1234-abcd",
  "causationId": "cmd-hold-5678",
  "aggregateId": "123e4567-e89b-12d3-a456-426614174000",
  "version": 1,
  "payload": { ... }
}
```

### 2.2 Domain Event Payload Specifications

#### `UserRegisteredEvent` (eventType: `"UserRegistered"`)
```json
{
  "userId": "323e4567-e89b-12d3-a456-426614174000",
  "email": "customer@seatflow.com",
  "registeredAt": "2026-08-24T10:00:00Z"
}
```
*Note: Consumed by `ticket-service` and `reservation-service` to automatically link historical guest purchases (`WHERE customer_email = :email AND user_id IS NULL`).*

#### `ReservationHeldEvent` (eventType: `"ReservationHeld"`)
```json
{
  "reservationId": "123e4567-e89b-12d3-a456-426614174000",
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "userId": "323e4567-e89b-12d3-a456-426614174000",
  "customerEmail": "customer@seatflow.com",
  "customerName": "Alex Smith",
  "seatIds": ["423e4567-e89b-12d3-a456-426614174000", "523e4567-e89b-12d3-a456-426614174000"],
  "expiresAt": "2026-08-23T14:45:00Z",
  "totalAmount": 150.00
}
```
*Note: `userId` is `null` when a guest creates the reservation without an account.*

#### `ReservationExpiredEvent` (eventType: `"ReservationExpired"`)
```json
{
  "reservationId": "123e4567-e89b-12d3-a456-426614174000",
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "seatIds": ["423e4567-e89b-12d3-a456-426614174000", "523e4567-e89b-12d3-a456-426614174000"],
  "reason": "HOLD_TIMEOUT_EXCEEDED"
}
```

#### `PaymentCompletedEvent` (eventType: `"PaymentCompleted"`)
```json
{
  "paymentId": "723e4567-e89b-12d3-a456-426614174000",
  "reservationId": "123e4567-e89b-12d3-a456-426614174000",
  "userId": "323e4567-e89b-12d3-a456-426614174000",
  "customerEmail": "customer@seatflow.com",
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "amount": 150.00,
  "currency": "USD",
  "stripePaymentId": "pi_3Nsk2e2eZvKYlo2C1gQ"
}
```
*Note: `userId` is `null` for guest payments.*

#### `TicketIssuedEvent` (eventType: `"TicketIssued"`)
```json
{
  "ticketId": "823e4567-e89b-12d3-a456-426614174000",
  "reservationId": "123e4567-e89b-12d3-a456-426614174000",
  "userId": "323e4567-e89b-12d3-a456-426614174000",
  "customerEmail": "customer@seatflow.com",
  "attendeeName": "Alex Smith",
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "seatId": "423e4567-e89b-12d3-a456-426614174000",
  "ticketCode": "SF-TKT-9876-ABCD",
  "qrCodeData": "SF://TKT/9876-ABCD/SIGNATURE"
}
```
*Note: `userId` is `null` for guest tickets.*

#### `SeatStatusUpdatedEvent` (eventType: `"SeatStatusUpdated"`)
```json
{
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "seatId": "423e4567-e89b-12d3-a456-426614174000",
  "status": "HELD",
  "updatedAt": "2026-08-23T14:30:00Z"
}
```

#### `VenueCreatedEvent` (eventType: `"VenueCreated"`)
```json
{
  "venueId": "923e4567-e89b-12d3-a456-426614174000",
  "name": "Grand Theatre",
  "city": "New York",
  "capacity": 500,
  "createdAt": "2026-08-23T10:00:00Z"
}
```

#### `VenueSectionCreatedEvent` (eventType: `"VenueSectionCreated"`)
```json
{
  "sectionId": "823e4567-e89b-12d3-a456-426614174000",
  "venueId": "923e4567-e89b-12d3-a456-426614174000",
  "name": "Orchestra",
  "rowCount": 10,
  "colCount": 20,
  "totalSeats": 200,
  "createdAt": "2026-08-23T10:05:00Z"
}
```

#### `EventCreatedEvent` (eventType: `"EventCreated"`)
```json
{
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "venueId": "923e4567-e89b-12d3-a456-426614174000",
  "title": "Hamlet — Royal Shakespeare Co.",
  "category": "THEATRE",
  "eventDate": "2026-09-15T19:30:00Z",
  "occurredAt": "2026-08-23T10:00:00Z"
}
```

#### `EventPublishedEvent` (eventType: `"EventPublished"`)
```json
{
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "venueId": "923e4567-e89b-12d3-a456-426614174000",
  "title": "Hamlet — Royal Shakespeare Co.",
  "category": "THEATRE",
  "eventDate": "2026-09-15T19:30:00Z",
  "occurredAt": "2026-08-23T11:00:00Z"
}
```

#### `EventCancelledEvent` (eventType: `"EventCancelled"`)
```json
{
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "venueId": "923e4567-e89b-12d3-a456-426614174000",
  "title": "Hamlet — Royal Shakespeare Co.",
  "eventDate": "2026-09-15T19:30:00Z",
  "occurredAt": "2026-08-23T12:00:00Z"
}
```

---

## 3. Transactional Outbox Pattern

### 3.1 Problem & Guarantee
Directly executing `kafkaTemplate.send()` inside a `@Transactional` business method can cause inconsistencies if Kafka succeeds but the DB rolls back (or vice-versa). 

With Outbox:
1. Domain state change AND event record are committed to PostgreSQL in the **same local ACID transaction**.
2. A separate publisher reads unpublished outbox rows and sends them to Kafka with at-least-once delivery.

### 3.2 Outbox Schema
```sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT chk_outbox_retry_count CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

---

---

## 4. Multi-Instance Horizontal Scaling & Consumer Groups

In production deployments where services scale across multiple instances / VMs / containers, Kafka guarantees that **only one instance of a specific microservice processes each event**, while allowing different microservices to receive a copy in parallel:

```text
                                 TOPIC: seatflow.payment.events
                               ┌────────────────────────────────┐
                               │ Partition 0 | Partition 1 | P2 │
                               └───────┬──────────┬─────────────┘
                                       │          │
                 ┌─────────────────────┴──────────┴─────────────────────┐
                 │                                                      │
                 ▼                                                      ▼
  Consumer Group: "ticket-service"                       Consumer Group: "notification-service"
  (Scale: 3 Instances / Pods)                            (Scale: 2 Instances / Pods)
  ┌─────────────────────────────────────────┐            ┌───────────────────────────────────────┐
  │  Instance 1 (VM 1) ──► Processes Msg 1  │            │  Instance 1 (VM A) ──► Processes Msg 1│
  │  Instance 2 (VM 2) ──► Idle / Msg 2     │            │  Instance 2 (VM B) ──► Idle / Msg 2   │
  │  Instance 3 (VM 3) ──► Idle / Msg 3     │            └───────────────────────────────────────┘
  └─────────────────────────────────────────┘
```

1. **Load Balancing within the same service:** All instances of `ticket-service` configure `groupId = "ticket-service"`. Kafka automatically assigns partitions among instances so that a single event is processed by **exactly one instance**.
2. **Publish-Subscribe across different services:** Different microservices use distinct group IDs (`ticket-service`, `notification-service`, `reservation-service`). Each service receives its own independent stream copy.

---

## 5. Consuming Multi-Event Topics & Polymorphic Dispatch

Topics organized by aggregate (e.g. `seatflow.user.events`, `seatflow.reservation.events`) carry multiple event types. Downstream consumers selectively process relevant events and silently ignore irrelevant ones using **Spring Kafka Polymorphic `@KafkaHandler`**:

```java
@Component
@KafkaListener(topics = EventTopics.USER_EVENTS, groupId = "ticket-service")
public class UserRegistrationEventListener {

    private final TicketService ticketService;

    // 1. Explicit handler for UserRegistered events
    @KafkaHandler
    public void handleUserRegistered(EventEnvelope<UserRegisteredEvent> envelope) {
        UserRegisteredEvent payload = envelope.payload();
        ticketService.claimHistoricalGuestTickets(payload.userId(), payload.email());
    }

    // 2. Default fallback: safely ignores other event types on the same topic (e.g. UserProfileUpdated)
    @KafkaHandler(isDefault = true)
    public void handleIgnoredEvents(Object genericEvent) {
        // No-op: event not relevant to ticket-service
    }
}
```

Alternatively, consumers can route via `switch(envelope.eventType())`:
```java
if ("UserRegistered".equals(envelope.eventType())) {
    // Process registration claiming
}
```

---

## 6. Idempotent Consumer Guidelines

Because Kafka provides **at-least-once** delivery (e.g. on network rebalances or retry attempts across instances), all consumer listeners must be strictly idempotent:

```java
@KafkaListener(topics = EventTopics.PAYMENT_EVENTS, groupId = "ticket-service")
public void handlePaymentEvent(EventEnvelope<PaymentCompletedEvent> envelope) {
    UUID paymentId = UUID.fromString(envelope.aggregateId());

    // 1. Idempotency check against database or processed_events ledger
    if (ticketRepository.existsByPaymentId(paymentId)) {
        log.info("Duplicate event ignored: eventId={}, paymentId={}", envelope.eventId(), paymentId);
        return;
    }

    // 2. Process domain logic
    ticketService.generateTicketsForPayment(envelope.payload());
}
```

---

## 7. End-to-End Asynchronous Choreography (Checkout Saga Flow)

```text
1. SEAT HOLD:
   User selects seats -> Reservation Service holds seats in PostgreSQL -> commits outbox event
   Outbox Publisher -> Kafka [seatflow.reservation.events] (ReservationHeldEvent)
   Realtime Service consumes event -> WebSocket STOMP broadcasts HELD status to all active browsers

2. PAYMENT SUCCESS:
   Stripe fires webhook -> Payment Service validates signature & idempotency -> updates payment to SUCCESS
   Payment Service commits outbox event -> Kafka [seatflow.payment.events] (PaymentCompletedEvent)
   
   Parallel Consumers:
   a) Ticket Service consumes PaymentCompletedEvent -> generates QR/PDF tickets -> commits TicketIssuedEvent
   b) Reservation Service consumes PaymentCompletedEvent -> updates status from PENDING to CONFIRMED
   c) Realtime Service consumes PaymentCompletedEvent -> broadcasts SOLD status to all active browsers

3. TICKET ISSUANCE & NOTIFICATION:
   Kafka [seatflow.ticket.events] (TicketIssuedEvent)
   Notification Service consumes TicketIssuedEvent -> renders Thymeleaf template -> dispatches email to customer

4. USER REGISTRATION (GUEST CLAIMING):
   User registers/logs in -> User Service commits outbox event -> Kafka [seatflow.user.events] (UserRegisteredEvent)
   Ticket Service consumes UserRegisteredEvent -> links historical tickets (UPDATE tickets SET user_id=... WHERE customer_email=... AND user_id IS NULL)
   Reservation Service consumes UserRegisteredEvent -> links historical reservations

5. HOLD TIMEOUT (EXPIRATION):
   Background Sweeper (Reservation Service) finds expired holds via SELECT FOR UPDATE SKIP LOCKED
   Updates status to EXPIRED -> commits outbox event -> Kafka [seatflow.reservation.events] (ReservationExpiredEvent)
   Realtime Service consumes event -> WebSocket STOMP broadcasts AVAILABLE status back to browsers
```
