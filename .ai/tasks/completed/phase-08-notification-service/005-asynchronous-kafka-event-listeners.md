# TASK-P08-005: Asynchronous Kafka Event Listeners & Envelope Unwrapping

## 1. Task Metadata
- **Task ID:** `TASK-P08-005`
- **Git Branch:** `feat/p08-001-notification-service`
- **Target Module:** `backend/services/notification-service`
- **Phase:** `Phase 08 - Notification Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/05-messaging-and-outbox.md`
- **Related ADRs:** `ADR-004: Stripe Tax and Tax-Inclusive Pricing`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Configure the Spring Kafka consumer infrastructure (`KafkaConsumerConfig`) with `EventEnvelope<T>` deserialization and default error handling, and implement asynchronous event listeners consuming from:
1. `seatflow.ticket.events` (`TicketIssuedEventListener`): Unwraps `TicketIssuedEvent`, retrieves PDF from `TicketServiceClient`, and invokes `NotificationService.sendTicketIssuedNotification`.
2. `seatflow.payment.events` (`PaymentFailedEventListener`): Unwraps `PaymentFailedEvent` and invokes `NotificationService.sendPaymentFailedNotification`.
3. `seatflow.reservation.events` (`ReservationHeldEventListener`): Unwraps `ReservationHeldEvent` and invokes `NotificationService.sendReservationHeldNotification`.

### Critical Invariants to Enforce:
- [x] **Kafka Group ID:** Consumer group ID is `notification-service`.
- [x] **Envelope Unwrapping:** Correctly deserialize and unwrap `EventEnvelope<T>` into domain event payload records (`TicketIssuedEvent`, `PaymentFailedEvent`, `ReservationHeldEvent`).
- [x] **Correlation & Trace Propagation:** Extract `correlationId` and `eventId` from envelope, populate `CorrelationContext` and SLF4J MDC (`correlationId`, `traceId`), and clear in `finally` block.
- [x] **Consumer Error Handling:** Record-level acknowledgment with `DefaultErrorHandler` and backoff.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/config/KafkaConsumerConfig.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/messaging/event/TicketIssuedEvent.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/messaging/event/PaymentFailedEvent.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/messaging/event/ReservationHeldEvent.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/messaging/consumer/TicketIssuedEventListener.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/messaging/consumer/PaymentFailedEventListener.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/messaging/consumer/ReservationHeldEventListener.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/messaging/consumer/TicketIssuedEventListenerTest.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/messaging/consumer/PaymentFailedEventListenerTest.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/messaging/consumer/ReservationHeldEventListenerTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Event Payload Records
```java
public record TicketIssuedEvent(
    UUID ticketId,
    UUID reservationId,
    UUID userId,
    String customerEmail,
    String attendeeName,
    UUID eventId,
    UUID seatId,
    BigDecimal price,
    BigDecimal taxAmount,
    BigDecimal netAmount,
    String ticketCode,
    String qrCodeData,
    Instant occurredAt
) implements DomainEvent {}

public record PaymentFailedEvent(
    UUID paymentId,
    UUID reservationId,
    UUID userId,
    String customerEmail,
    UUID eventId,
    BigDecimal amount,
    String currency,
    String stripePaymentId,
    String failureReason,
    Instant occurredAt
) implements DomainEvent {}

public record ReservationHeldEvent(
    UUID reservationId,
    UUID eventId,
    UUID userId,
    String customerEmail,
    List<UUID> seatIds,
    Instant expiresAt,
    BigDecimal totalAmount,
    Instant occurredAt
) implements DomainEvent {}
```

---

## 5. Step-by-Step Implementation Sequence
1. Create `KafkaConsumerConfig` with `EventEnvelope` deserializer, non-blocking error handler, and container factory.
2. Define event payload records implementing `DomainEvent`.
3. Implement `TicketIssuedEventListener` consuming from `EventTopics.TICKET_EVENTS`.
4. Implement `PaymentFailedEventListener` consuming from `EventTopics.PAYMENT_EVENTS`.
5. Implement `ReservationHeldEventListener` consuming from `EventTopics.RESERVATION_EVENTS`.
6. Write unit and slice tests for all three event listeners verifying payload extraction and MDC context management.

---

## 6. Definition of Done & Verification Command
```bash
mvn clean test -pl backend/services/notification-service -Dtest=*EventListenerTest
```
