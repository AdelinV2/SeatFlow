# TASK-P06-007: Asynchronous Kafka Event Listeners (Payment Completion & Guest Claiming)

## 1. Task Metadata
- **Task ID:** `TASK-P06-007`
- **Git Branch:** `feat/p06-007-asynchronous-kafka-event-listeners`
- **Target Module:** `backend/services/ticket-service`
- **Phase:** `Phase 06 - Ticket & QR Code Service`
- **Related Specs:** `.ai/architecture/05-messaging-and-outbox.md` (Sections 4, 5, 6, 7), `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-004-stripe-tax-and-tax-inclusive-pricing.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the idempotent Kafka event consumers for `ticket-service`. The service consumes `PaymentCompletedEvent` from `seatflow.payment.events` to automatically issue digital tickets with QR codes and fiscal tax breakdown, and consumes `UserRegisteredEvent` from `seatflow.user.events` to link historical guest ticket purchases to newly registered customer accounts (ADR-001).

### Critical Invariants to Enforce:
- [ ] **Strict Idempotency Guard:** `PaymentCompletedEventListener` must check `ticketRepository.existsByPaymentId(paymentId)` before issuing tickets. If tickets already exist for the payment, immediately log INFO and skip processing to guarantee zero duplicate tickets on Kafka message replay.
- [ ] **Consumer Group Isolation:** All consumers in `ticket-service` use `groupId = "ticket-service"` to support multi-instance load balancing.
- [ ] **Proportional Fiscal Tax Allocation (ADR-004):** When a payment covers multiple seats in a reservation, distribute `taxAmount` and `netAmount` proportionally/equally across the issued seat tickets so each ticket maintains a mathematically sound fiscal breakdown.
- [ ] **Automatic Guest Claiming (ADR-001):** `UserRegisteredEventListener` consumes `UserRegisteredEvent` and calls `ticketService.claimGuestTickets(userId, email)` to update all matching unlinked tickets in PostgreSQL (`WHERE customer_email = :email AND user_id IS NULL`).
- [ ] **Transactional Outbox Continuity:** Digital ticket creation from `PaymentCompletedEventListener` persists `Ticket` records and commits new `TicketIssuedEvent` outbox rows in the same transaction for downstream consumption by `notification-service`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/messaging/event/PaymentCompletedEvent.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/messaging/event/UserRegisteredEvent.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/config/KafkaConsumerConfig.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/messaging/consumer/PaymentCompletedEventListener.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/messaging/consumer/UserRegisteredEventListener.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/messaging/consumer/PaymentCompletedEventListenerIntegrationTest.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/messaging/consumer/UserRegisteredEventListenerIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Consumed Domain Event Records
Create in `com.seatflow.ticket.messaging.event`:

#### `PaymentCompletedEvent.java`
```java
package com.seatflow.ticket.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event received when a payment is successfully processed")
public record PaymentCompletedEvent(
    UUID paymentId,
    UUID reservationId,
    UUID userId,              // Nullable for guest checkouts (ADR-001)
    String customerEmail,
    UUID eventId,
    BigDecimal amount,        // Total gross amount charged
    BigDecimal taxAmount,     // Tax portion computed by Stripe Tax (ADR-004)
    BigDecimal netAmount,     // Net merchant revenue portion
    String currency,
    String stripePaymentId,
    Instant occurredAt
) implements DomainEvent {}
```

#### `UserRegisteredEvent.java`
```java
package com.seatflow.ticket.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event received when a user registers an account")
public record UserRegisteredEvent(
    UUID userId,
    String email,
    Instant registeredAt
) implements DomainEvent {}
```

---

### 4.2 Kafka Consumer Configuration
`config/KafkaConsumerConfig.java`:
```java
package com.seatflow.ticket.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ticket-service");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.seatflow.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));
        return factory;
    }
}
```

---

### 4.3 Payment Completed Event Listener
`messaging/consumer/PaymentCompletedEventListener.java`:
```java
package com.seatflow.ticket.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.ticket.client.ReservationServiceClient;
import com.seatflow.ticket.client.dto.ReservationClientResponse;
import com.seatflow.ticket.messaging.event.PaymentCompletedEvent;
import com.seatflow.ticket.model.common.IssueTicketsCommand;
import com.seatflow.ticket.repository.TicketRepository;
import com.seatflow.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCompletedEventListener {

    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final ReservationServiceClient reservationServiceClient;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = EventTopics.PAYMENT_EVENTS,
        groupId = "ticket-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentCompleted(EventEnvelope<Object> envelope) {
        if (!"PaymentCompleted".equals(envelope.eventType())) {
            log.debug("Ignoring irrelevant payment event type: {}", envelope.eventType());
            return;
        }

        PaymentCompletedEvent payload = objectMapper.convertValue(envelope.payload(), PaymentCompletedEvent.class);
        UUID paymentId = payload.paymentId();

        log.info("Received PaymentCompleted event. paymentId={}, reservationId={}, amount={}",
                paymentId, payload.reservationId(), payload.amount());

        // 1. Strict Idempotency Check
        if (ticketRepository.existsByPaymentId(paymentId)) {
            log.info("Duplicate PaymentCompleted event skipped. Tickets already exist for paymentId={}", paymentId);
            return;
        }

        // 2. Fetch reservation details from reservation-service
        ReservationClientResponse reservation = reservationServiceClient.getReservationById(payload.reservationId())
                .orElseThrow(() -> new IllegalStateException("Reservation not found for payment: " + payload.reservationId()));

        List<ReservationClientResponse.HeldSeatClientDto> seats = reservation.seats();
        if (seats == null || seats.isEmpty()) {
            throw new IllegalStateException("No seats associated with reservation: " + payload.reservationId());
        }

        int seatCount = seats.size();
        BigDecimal totalAmount = payload.amount();
        BigDecimal totalTax = payload.taxAmount() != null ? payload.taxAmount() : BigDecimal.ZERO;
        BigDecimal totalNet = payload.netAmount() != null ? payload.netAmount() : totalAmount.subtract(totalTax);

        BigDecimal baseSeatTax = totalTax.divide(BigDecimal.valueOf(seatCount), 2, RoundingMode.FLOOR);
        BigDecimal baseSeatNet = totalNet.divide(BigDecimal.valueOf(seatCount), 2, RoundingMode.FLOOR);
        BigDecimal taxRemainder = totalTax.subtract(baseSeatTax.multiply(BigDecimal.valueOf(seatCount)));
        BigDecimal netRemainder = totalNet.subtract(baseSeatNet.multiply(BigDecimal.valueOf(seatCount)));

        List<IssueTicketsCommand.SeatTicketItem> ticketItems = new ArrayList<>();
        for (int i = 0; i < seatCount; i++) {
            ReservationClientResponse.HeldSeatClientDto seat = seats.get(i);
            boolean isLast = (i == seatCount - 1);
            BigDecimal seatTax = isLast ? baseSeatTax.add(taxRemainder) : baseSeatTax;
            BigDecimal seatNet = isLast ? baseSeatNet.add(netRemainder) : baseSeatNet;

            ticketItems.add(new IssueTicketsCommand.SeatTicketItem(
                    seat.seatId(),
                    seat.price(),
                    seatTax,
                    seatNet
            ));
        }

        // 3. Issue digital tickets
        IssueTicketsCommand command = new IssueTicketsCommand(
                payload.paymentId(),
                payload.reservationId(),
                payload.userId(),
                payload.customerEmail(),
                payload.customerEmail(),
                payload.eventId(),
                ticketItems,
                payload.currency()
        );

        ticketService.issueTickets(command);

        log.info("Digital tickets issued successfully for paymentId={}, seatCount={}", paymentId, seatCount);
    }
}
```

---

### 4.4 User Registered Event Listener (Guest Auto-Claiming)
`messaging/consumer/UserRegisteredEventListener.java`:
```java
package com.seatflow.ticket.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.ticket.messaging.event.UserRegisteredEvent;
import com.seatflow.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserRegisteredEventListener {

    private final TicketService ticketService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = EventTopics.USER_EVENTS,
        groupId = "ticket-service",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onUserRegistered(EventEnvelope<Object> envelope) {
        if (!"UserRegistered".equals(envelope.eventType())) {
            log.debug("Ignoring irrelevant user event type: {}", envelope.eventType());
            return;
        }

        UserRegisteredEvent payload = objectMapper.convertValue(envelope.payload(), UserRegisteredEvent.class);

        log.info("Processing UserRegistered event for guest ticket linking. userId={}, email={}",
                payload.userId(), payload.email());

        int claimedCount = ticketService.claimGuestTickets(payload.userId(), payload.email());

        log.info("Guest ticket linking complete. userId={}, claimedCount={}", payload.userId(), claimedCount);
    }
}
```

---

### 4.5 Integration Testing Contracts

#### `PaymentCompletedEventListenerIntegrationTest`:
- Uses `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Mocks `ReservationServiceClient` to return sample reservation with 2 seats ($100 total, $19 tax, $81 net).
- Publishes `PaymentCompletedEvent` to `seatflow.payment.events` via `KafkaTemplate`.
- Awaits listener execution (via Awaitility or latch).
- Asserts:
  1. 2 `Ticket` entities are created in the database.
  2. Each ticket has `price = 50.00`, `tax_amount = 9.50`, `net_amount = 40.50`.
  3. 2 `TicketIssued` records are created in `outbox_events`.
  4. Resending the same event does not create duplicate tickets.

#### `UserRegisteredEventListenerIntegrationTest`:
- Uses `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Seeds 3 guest tickets with `userId = null` and `customer_email = "guest.shopper@seatflow.com"`.
- Publishes `UserRegisteredEvent(userId = newUserId, email = "guest.shopper@seatflow.com")` to `seatflow.user.events`.
- Awaits listener execution.
- Asserts all 3 tickets in PostgreSQL now have `user_id = newUserId`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p06-007-asynchronous-kafka-event-listeners` from `develop`.
2. Define event records `PaymentCompletedEvent` and `UserRegisteredEvent` in `messaging/event/`.
3. Configure `KafkaConsumerConfig.java` in `config/`.
4. Implement `PaymentCompletedEventListener.java` with idempotency verification, reservation service integration, fiscal tax distribution, and ticket issuance.
5. Implement `UserRegisteredEventListener.java` calling `ticketService.claimGuestTickets`.
6. Write integration tests `PaymentCompletedEventListenerIntegrationTest` and `UserRegisteredEventListenerIntegrationTest` using Testcontainers PostgreSQL and Kafka.
7. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/ticket-service -Dtest=*EventListenerIntegrationTest
```

- [ ] All Kafka listeners and consumer configurations compile cleanly.
- [ ] `PaymentCompletedEventListener` issues tickets and outbox events upon valid payment completion.
- [ ] Idempotency guarantee verified: duplicate payment events are safely ignored.
- [ ] `UserRegisteredEventListener` links historical guest purchases to newly registered users (ADR-001).
- [ ] All integration tests pass against PostgreSQL 16 and Kafka Testcontainers.
- [ ] Task file is moved to `.ai/tasks/completed/phase-06-ticket-service/007-asynchronous-kafka-event-listeners.md` when complete.
