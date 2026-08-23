# 05 — Messaging & Transactional Outbox Specification

SeatFlow uses **Apache Kafka** as its asynchronous backbone for decoupled event-driven workflows, combined with the **Transactional Outbox Pattern** to eliminate dual-write hazards.

---

## 1. Kafka Cluster & Topic Catalog

All Kafka topics are partitioned and keyed by their primary aggregate identifier (e.g. `reservationId`, `eventId`, `paymentId`) to guarantee in-order delivery per entity.

| Topic Name | Producer(s) | Consumer(s) | Key | Purpose |
|---|---|---|---|---|
| `seatflow.reservation.events` | Reservation Service | Realtime, Notification | `reservationId` | Reservation held, expired, confirmed, or cancelled |
| `seatflow.payment.events` | Payment Service | Ticket, Notification, Reservation | `paymentId` | Payment completed, failed, refunded |
| `seatflow.ticket.events` | Ticket Service | Notification | `ticketId` | Ticket generated and issued |
| `seatflow.seat.status.events` | (Removed/Deprecated) | | | Replaced by direct subscription to reservation and payment topics |

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

#### `ReservationHeldEvent` (eventType: `"ReservationHeld"`)
```json
{
  "reservationId": "123e4567-e89b-12d3-a456-426614174000",
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "userId": "323e4567-e89b-12d3-a456-426614174000",
  "seatIds": ["423e4567-e89b-12d3-a456-426614174000", "523e4567-e89b-12d3-a456-426614174000"],
  "expiresAt": "2026-08-23T14:45:00Z",
  "totalAmount": 150.00
}
```

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
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "amount": 150.00,
  "currency": "USD",
  "stripePaymentId": "pi_3Nsk2e2eZvKYlo2C1gQ"
}
```

#### `TicketIssuedEvent` (eventType: `"TicketIssued"`)
```json
{
  "ticketId": "823e4567-e89b-12d3-a456-426614174000",
  "reservationId": "123e4567-e89b-12d3-a456-426614174000",
  "userId": "323e4567-e89b-12d3-a456-426614174000",
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "seatId": "423e4567-e89b-12d3-a456-426614174000",
  "ticketCode": "SF-TKT-9876-ABCD",
  "qrCodeData": "SF://TKT/9876-ABCD/SIGNATURE"
}
```

#### `SeatStatusUpdatedEvent` (eventType: `"SeatStatusUpdated"`)
```json
{
  "eventId": "223e4567-e89b-12d3-a456-426614174000",
  "seatId": "423e4567-e89b-12d3-a456-426614174000",
  "status": "HELD",
  "updatedAt": "2026-08-23T14:30:00Z"
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
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,
    CONSTRAINT max_retries CHECK (retry_count <= 5)
);
CREATE INDEX idx_outbox_unpub ON outbox_events(created_at) WHERE published_at IS NULL;
```

---

## 4. Idempotent Consumer Guidelines

Because Kafka provides **at-least-once** delivery, consumers must be strictly idempotent:

```java
@KafkaListener(topics = EventTopics.PAYMENT_EVENTS, groupId = "ticket-service")
public void handlePaymentEvent(EventEnvelope<PaymentCompletedEvent> envelope) {
    UUID paymentId = UUID.fromString(envelope.aggregateId());

    // 1. Idempotency check against domain entity or processed_events table
    if (ticketRepository.existsByPaymentId(paymentId)) {
        log.info("Duplicate event ignored: eventId={}, paymentId={}", envelope.eventId(), paymentId);
        return;
    }

    // 2. Process domain logic
    ticketService.generateTicketsForPayment(envelope.payload());
}
```

---

## 5. End-to-End Asynchronous Choreography (Checkout Saga Flow)

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

4. HOLD TIMEOUT (EXPIRATION):
   Background Sweeper (Reservation Service) finds expired holds via SELECT FOR UPDATE SKIP LOCKED
   Updates status to EXPIRED -> commits outbox event -> Kafka [seatflow.reservation.events] (ReservationExpiredEvent)
   Realtime Service consumes event -> WebSocket STOMP broadcasts AVAILABLE status back to browsers
```
