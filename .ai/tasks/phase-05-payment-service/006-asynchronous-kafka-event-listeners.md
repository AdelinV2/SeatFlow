# TASK-P05-006: Asynchronous Kafka Event Listeners: Guest Payment Account Linking

## 1. Task Metadata
- **Task ID:** `TASK-P05-006`
- **Git Branch:** `feat/p05-006-asynchronous-kafka-event-listeners`
- **Target Module:** `backend/services/payment-service`
- **Phase:** `Phase 05 - Payment & Stripe Service`
- **Related Specs:** `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/02-microservices-spec.md` (Section 7), `.ai/architecture/03-database-models.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement asynchronous Kafka consumer listeners in `payment-service` for event-driven cross-service synchronization. Specifically, consume `UserRegisteredEvent` from `user-service` to automatically associate historical guest payment records with newly registered customer accounts per ADR-001.

### Critical Invariants to Enforce:
- [ ] **ADR-001 (Automatic Guest Account Linking):** When `UserRegisteredEvent` arrives on `seatflow.user.events`, execute an atomic update linking all historical payments matching `customer_email = :email AND user_id IS NULL` to the newly registered `userId`.
- [ ] **Consumer Idempotency:** If `UserRegisteredEvent` is re-delivered, executing the `UPDATE ... WHERE customer_email = :email AND user_id IS NULL` query is idempotent and results in 0 rows updated without throwing errors.
- [ ] **Kafka Consumer Group:** Set to `payment-service` for topic listeners.
- [ ] **Zero Poison Pills / Polymorphic Event Handling:** Check `eventType` before binding payload records to ensure non-matching topic events (e.g. `UserProfileUpdated`) are safely ignored without triggering deserialization failures or consumer retry loops.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/config/KafkaConsumerConfig.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/messaging/event/UserRegisteredEvent.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/messaging/consumer/UserEventsConsumer.java`
- `[MODIFY]` `backend/services/payment-service/src/main/java/com/seatflow/payment/service/PaymentService.java` — add `claimGuestPayments(UUID userId, String customerEmail)`.
- `[MODIFY]` `backend/services/payment-service/src/main/java/com/seatflow/payment/service/impl/PaymentServiceImpl.java` — implement `claimGuestPayments`.
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/messaging/consumer/UserEventsConsumerTest.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/integration/KafkaConsumerIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Inbound Domain Event Record
Create `UserRegisteredEvent.java` in `com.seatflow.payment.messaging.event`:

```java
package com.seatflow.payment.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Inbound event published by user-service upon user registration")
public record UserRegisteredEvent(
    UUID userId,
    String email,
    Instant registeredAt
) implements DomainEvent {}
```

---

### 4.2 Kafka Consumer Configuration
`KafkaConsumerConfig.java` in `com.seatflow.payment.config`:

```java
package com.seatflow.payment.config;

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

    @Value("${spring.kafka.consumer.group-id:payment-service}")
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

### 4.3 Service Method Additions

Add to `PaymentService.java`:
```java
int claimGuestPayments(UUID userId, String customerEmail);
```

Add to `PaymentServiceImpl.java`:
```java
@Override
@Transactional
public int claimGuestPayments(UUID userId, String customerEmail) {
    log.info("Claiming historical guest payments for newly registered user. userId={}, email={}", userId, customerEmail);
    int updatedCount = paymentRepository.updateUserIdForCustomerEmail(userId, customerEmail, Instant.now());
    log.info("Claimed historical guest payments. userId={}, email={}, count={}", userId, customerEmail, updatedCount);
    return updatedCount;
}
```

---

### 4.4 User Events Consumer Contract
`UserEventsConsumer.java` in `com.seatflow.payment.messaging.consumer`:

```java
package com.seatflow.payment.messaging.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.payment.messaging.event.UserRegisteredEvent;
import com.seatflow.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventsConsumer {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EventTopics.USER_EVENTS, groupId = "payment-service")
    public void handleUserEvents(
            String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            String eventType = rootNode.path("eventType").asText();
            String eventId = rootNode.path("eventId").asText();
            String correlationId = rootNode.path("correlationId").asText();
            String aggregateId = rootNode.path("aggregateId").asText();

            log.info("Processing Kafka event. topic={}, eventType={}, eventId={}, aggregateId={}, correlationId={}, partition={}, offset={}",
                    topic, eventType, eventId, aggregateId, correlationId, partition, offset);

            if ("UserRegistered".equalsIgnoreCase(eventType)) {
                EventEnvelope<UserRegisteredEvent> envelope = objectMapper.readValue(
                        message,
                        new TypeReference<>() {}
                );
                UserRegisteredEvent payload = envelope.payload();
                int linked = paymentService.claimGuestPayments(payload.userId(), payload.email());
                log.info("Guest payments linked to registered user. userId={}, email={}, linkedCount={}",
                        payload.userId(), payload.email(), linked);
            } else {
                log.debug("Ignored irrelevant user event type on topic {}: {}", topic, eventType);
            }
        } catch (Exception ex) {
            log.error("Failed to process user event message: topic={}, partition={}, offset={}", topic, partition, offset, ex);
            throw new RuntimeException("Error processing UserRegistered event", ex);
        }
    }
}
```

---

### 4.5 Consumer Unit & Integration Tests Contract
- `UserEventsConsumerTest`: Mockito unit tests verifying:
  - Valid `UserRegistered` JSON envelope triggers `paymentService.claimGuestPayments(userId, email)`.
  - Irrelevant event types (e.g. `UserProfileUpdated`) are safely ignored without errors.
- `KafkaConsumerIntegrationTest`: Testcontainers PostgreSQL 16 and Kafka integration test:
  1. Creates a guest payment record with `userId = null` and `customerEmail = "guest@seatflow.com"`.
  2. Publishes a `UserRegisteredEvent` to `seatflow.user.events` for `"guest@seatflow.com"` with a generated `userId`.
  3. Awaits consumer execution.
  4. Asserts the payment's `userId` is updated from `null` to the new `userId`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p05-006-asynchronous-kafka-event-listeners` from `develop`.
2. Create `UserRegisteredEvent` record implementing `DomainEvent`.
3. Configure `KafkaConsumerConfig` with `ErrorHandlingDeserializer` and record acknowledgment.
4. Add `claimGuestPayments` method to `PaymentService` and `PaymentServiceImpl`.
5. Implement `UserEventsConsumer` with `eventType` filtering.
6. Write Mockito unit test `UserEventsConsumerTest`.
7. Write Testcontainers Kafka integration test `KafkaConsumerIntegrationTest`.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/payment-service -Dtest=UserEventsConsumerTest,KafkaConsumerIntegrationTest
```

- [ ] `UserRegisteredEvent` claims all unlinked guest payments by email.
- [ ] Duplicate user registration events are handled idempotently.
- [ ] Non-matching event types on `seatflow.user.events` are ignored safely without deserialization errors.
- [ ] Integration test passes with real Kafka and PostgreSQL Testcontainers.
- [ ] Task file is moved to `.ai/tasks/completed/phase-05-payment-service/006-asynchronous-kafka-event-listeners.md` when complete.
