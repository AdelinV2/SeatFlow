# TASK-P04-006: Asynchronous Kafka Event Listeners: Payment Confirmation & Guest Account Claiming

## 1. Task Metadata
- **Task ID:** `TASK-P04-006`
- **Git Branch:** `feat/p04-006-asynchronous-kafka-event-listeners`
- **Target Module:** `backend/services/reservation-service`
- **Phase:** `Phase 04 - Reservation & Hold Service`
- **Related Specs:** `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/02-microservices-spec.md` (Section 6), `.ai/architecture/03-database-models.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement asynchronous Kafka consumers in `reservation-service` for event-driven coordination across microservices. Specifically, consume `PaymentCompletedEvent` from `payment-service` to transition reservations from `PENDING` to `CONFIRMED` and seat holds to `SOLD`, and consume `UserRegisteredEvent` from `user-service` to automatically link historical guest reservations to newly registered user accounts per ADR-001.

### Critical Invariants to Enforce:
- [ ] **Consumer Idempotency:** If `PaymentCompletedEvent` arrives for an already `CONFIRMED` reservation, log INFO and safely acknowledge without throwing duplicate errors or reprocessing.
- [ ] **State Machine Enforcement:** When `PaymentCompleted` is received, if the reservation is `PENDING`, update status to `CONFIRMED` and all associated `SeatHold` records from `HELD` to `SOLD`. If the reservation is `EXPIRED` or `CANCELLED`, log a WARN/ERROR for administrative reconciliation without failing the consumer.
- [ ] **ADR-001 (Automatic Guest Account Linking):** When `UserRegisteredEvent` arrives on `seatflow.user.events`, execute an atomic update linking all historical reservations matching `customer_email = :email AND user_id IS NULL` to the newly provisioned `userId`.
- [ ] **Kafka Consumer Group:** Set to `reservation-service` across all topic listeners.
- [ ] **Zero Poison Pills / Polymorphic Event Handling:** Check `eventType` before binding payload records to ensure non-matching topic events (e.g. `PaymentFailed`, `UserProfileUpdated`) are safely ignored without triggering deserialization failures or consumer retry loops.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/config/KafkaConsumerConfig.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/messaging/event/PaymentCompletedEvent.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/messaging/event/UserRegisteredEvent.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/messaging/consumer/PaymentEventsConsumer.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/messaging/consumer/UserEventsConsumer.java`
- `[MODIFY]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/service/ReservationService.java` — add `confirmReservation(UUID reservationId, UUID paymentId)` and `claimGuestReservations(UUID userId, String email)`.
- `[MODIFY]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/service/impl/ReservationServiceImpl.java` — implement `confirmReservation` and `claimGuestReservations`.
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/messaging/consumer/PaymentEventsConsumerTest.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/messaging/consumer/UserEventsConsumerTest.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/integration/KafkaConsumerIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Inbound Domain Event Records

```java
package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Inbound event published by payment-service when a payment succeeds")
public record PaymentCompletedEvent(
    UUID paymentId,
    UUID reservationId,
    UUID userId,
    String customerEmail,
    UUID eventId,
    BigDecimal amount,
    String currency,
    String stripePaymentId,
    Instant occurredAt
) implements DomainEvent {}

@Schema(description = "Inbound event published by user-service upon user registration")
public record UserRegisteredEvent(
    UUID userId,
    String email,
    String name,
    Instant registeredAt
) implements DomainEvent {}
```

---

### 4.2 Kafka Consumer Configuration
`KafkaConsumerConfig.java` in `com.seatflow.reservation.config`:

```java
package com.seatflow.reservation.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:reservation-service}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));
        return factory;
    }
}
```

---

### 4.3 Service Methods for Event Consumption
Add to `ReservationService.java`:
```java
void confirmReservation(UUID reservationId, UUID paymentId);
int claimGuestReservations(UUID userId, String customerEmail);
```

Add to `ReservationServiceImpl.java`:
```java
@Override
@Transactional
public void confirmReservation(UUID reservationId, UUID paymentId) {
    log.info("Processing reservation payment confirmation. reservationId={}, paymentId={}", reservationId, paymentId);

    Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
            .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

    if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
        log.info("Reservation already CONFIRMED (idempotent skip). reservationId={}", reservationId);
        return;
    }

    if (reservation.getStatus() != ReservationStatus.PENDING) {
        log.error("Received payment confirmation for reservation with non-PENDING status. reservationId={}, status={}, paymentId={}",
                reservationId, reservation.getStatus(), paymentId);
        return;
    }

    reservation.setStatus(ReservationStatus.CONFIRMED);
    reservation.setUpdatedAt(Instant.now());

    for (SeatHold hold : reservation.getSeatHolds()) {
        hold.setStatus(SeatHoldStatus.SOLD);
    }

    reservationRepository.save(reservation);
    log.info("Reservation confirmed and seats transitioned to SOLD. reservationId={}, seatCount={}",
            reservationId, reservation.getSeatHolds().size());
}

@Override
@Transactional
public int claimGuestReservations(UUID userId, String customerEmail) {
    log.info("Claiming historical guest reservations for newly registered user. userId={}, email={}", userId, customerEmail);
    int updatedCount = reservationRepository.updateUserIdForGuestEmail(userId, customerEmail, Instant.now());
    log.info("Claimed historical guest reservations. userId={}, email={}, count={}", userId, customerEmail, updatedCount);
    return updatedCount;
}
```

---

### 4.4 Kafka Consumers Contract

#### `PaymentEventsConsumer.java`
```java
package com.seatflow.reservation.messaging.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.reservation.messaging.event.PaymentCompletedEvent;
import com.seatflow.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventsConsumer {

    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EventTopics.PAYMENT_EVENTS, groupId = "reservation-service")
    public void handlePaymentEvents(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            String eventType = rootNode.path("eventType").asText();

            if ("PaymentCompleted".equalsIgnoreCase(eventType)) {
                EventEnvelope<PaymentCompletedEvent> envelope = objectMapper.readValue(
                        message,
                        new TypeReference<>() {}
                );
                PaymentCompletedEvent payload = envelope.payload();
                log.info("Received PaymentCompleted event. paymentId={}, reservationId={}, correlationId={}",
                        payload.paymentId(), payload.reservationId(), envelope.correlationId());
                reservationService.confirmReservation(payload.reservationId(), payload.paymentId());
            } else {
                log.debug("Ignored irrelevant payment event type on topic {}: {}", EventTopics.PAYMENT_EVENTS, eventType);
            }
        } catch (Exception ex) {
            log.error("Failed to process payment event message: {}", message, ex);
            throw new RuntimeException("Error processing PaymentCompleted event", ex);
        }
    }
}
```

#### `UserEventsConsumer.java`
```java
package com.seatflow.reservation.messaging.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.reservation.messaging.event.UserRegisteredEvent;
import com.seatflow.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventsConsumer {

    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EventTopics.USER_EVENTS, groupId = "reservation-service")
    public void handleUserEvents(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            String eventType = rootNode.path("eventType").asText();

            if ("UserRegistered".equalsIgnoreCase(eventType)) {
                EventEnvelope<UserRegisteredEvent> envelope = objectMapper.readValue(
                        message,
                        new TypeReference<>() {}
                );
                UserRegisteredEvent payload = envelope.payload();
                log.info("Received UserRegistered event. userId={}, email={}, correlationId={}",
                        payload.userId(), payload.email(), envelope.correlationId());
                reservationService.claimGuestReservations(payload.userId(), payload.email());
            } else {
                log.debug("Ignored irrelevant user event type on topic {}: {}", EventTopics.USER_EVENTS, eventType);
            }
        } catch (Exception ex) {
            log.error("Failed to process user event message: {}", message, ex);
            throw new RuntimeException("Error processing UserRegistered event", ex);
        }
    }
}
```

---

### 4.5 Consumer Unit & Integration Tests Contract
- `PaymentEventsConsumerTest`: Mockito unit tests verifying that valid `PaymentCompleted` JSON envelope triggers `reservationService.confirmReservation(reservationId, paymentId)`, and non-matching event types are safely ignored without exceptions.
- `UserEventsConsumerTest`: Mockito unit tests verifying that `UserRegistered` envelope triggers `reservationService.claimGuestReservations(userId, email)`, and non-matching event types are safely ignored.
- `KafkaConsumerIntegrationTest`: Testcontainers PostgreSQL 16 and Kafka integration test:
  1. Creates a `PENDING` reservation with `userId = null` and `customerEmail = "guest@seatflow.com"`.
  2. Publishes a `PaymentCompletedEvent` JSON envelope to `seatflow.payment.events`.
  3. Awaits consumer execution and asserts `Reservation` is updated to `CONFIRMED` and `SeatHold` is updated to `SOLD`.
  4. Publishes a `UserRegisteredEvent` to `seatflow.user.events` for `"guest@seatflow.com"`.
  5. Asserts the reservation's `userId` is updated from `null` to the registered `userId`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p04-006-asynchronous-kafka-event-listeners` from `develop`.
2. Create `PaymentCompletedEvent` and `UserRegisteredEvent` records.
3. Configure `KafkaConsumerConfig` with `ErrorHandlingDeserializer` and manual/record acknowledgment.
4. Add `confirmReservation` and `claimGuestReservations` methods to `ReservationService` and `ReservationServiceImpl`.
5. Implement `PaymentEventsConsumer` and `UserEventsConsumer` with `eventType` pre-filtering.
6. Write Mockito unit tests `PaymentEventsConsumerTest` and `UserEventsConsumerTest`.
7. Write Testcontainers Kafka integration test `KafkaConsumerIntegrationTest`.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/reservation-service -Dtest=PaymentEventsConsumerTest,UserEventsConsumerTest,KafkaConsumerIntegrationTest
```

- [ ] `PaymentCompletedEvent` transitions reservation from `PENDING` to `CONFIRMED` and holds to `SOLD`.
- [ ] Duplicate payment events are handled idempotently.
- [ ] Heterogeneous event types on topics are ignored safely without deserialization errors.
- [ ] `UserRegisteredEvent` claims all unlinked guest reservations by email.
- [ ] Integration test passes with real Kafka and PostgreSQL Testcontainers.
- [ ] Task file is moved to `.ai/tasks/completed/phase-04-reservation-service/006-asynchronous-kafka-event-listeners.md` when complete.
