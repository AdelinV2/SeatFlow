# TASK-P05-005: Payment Outbox Event Publisher, Kafka Producer & Integration Test

## 1. Task Metadata
- **Task ID:** `TASK-P05-005`
- **Git Branch:** `feat/p05-005-outbox-event-publisher-and-kafka-producer`
- **Target Module:** `backend/services/payment-service`
- **Phase:** `Phase 05 - Payment & Stripe Service`
- **Related Specs:** `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/03-database-models.md` (Section 2.5), `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`, `.ai/decisions/ADR-004-stripe-tax-and-tax-inclusive-pricing.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the Transactional Outbox background publisher and Kafka producer pipeline in `payment-service`. Deliver persisted outbox domain events (`PaymentCompletedEvent`, `PaymentFailedEvent`) to the `seatflow.payment.events` Kafka topic with guaranteed at-least-once delivery, retry backoff, and multi-instance concurrency safety.

### Critical Invariants to Enforce:
- [ ] **Dual-Write Elimination:** Domain transactions commit state changes and serialized `EventEnvelope<DomainEvent>` to `outbox_events` only. The scheduled `OutboxEventPublisher` asynchronously delivers durable messages to Kafka.
- [ ] **Multi-Instance Safe Polling:** Publisher queries outbox rows using `SELECT ... FOR UPDATE SKIP LOCKED` (utilizing partial index `idx_pay_outbox_unpub` on `created_at ASC WHERE published_at IS NULL`).
- [ ] **Retry Ceiling:** Publisher limits retries to `MAX_RETRY_COUNT = 5`. Consecutive failures increment `retry_count`. Once max retries are exceeded, the event remains parked with `published_at = NULL` and structured error logs are emitted.
- [ ] **Target Kafka Topic:** Defaults to `seatflow.payment.events` (configured via `EventTopics.PAYMENT_EVENTS`). The message key is the `aggregateId` (UUID string of the `paymentId`), ensuring ordered per-payment processing on Kafka partitions.
- [ ] **End-to-End Integration Verification:** Test uses Testcontainers PostgreSQL 16 to execute a full payment lifecycle: PaymentIntent creation, webhook success transition, outbox persistence, and successful asynchronous publishing.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/config/KafkaProducerConfig.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/messaging/producer/OutboxEventPublisher.java`
- `[MODIFY]` `backend/services/payment-service/src/main/resources/application-local.yaml` — configure Kafka producer serializers.
- `[MODIFY]` `backend/services/payment-service/src/main/resources/application-docker.yaml` — configure Kafka producer serializers.
- `[MODIFY]` `backend/services/payment-service/src/main/resources/application-prod.yaml` — configure Kafka producer with `acks: all`, retries, and idempotence.
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/messaging/producer/OutboxEventPublisherTest.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/integration/PaymentServiceIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Kafka Producer Configuration
`KafkaProducerConfig.java` in `com.seatflow.payment.config`:

```java
package com.seatflow.payment.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.producer.acks:all}") String acks,
            @Value("${spring.kafka.producer.retries:3}") int retries) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, acks);
        config.put(ProducerConfig.RETRIES_CONFIG, retries);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
```

---

### 4.2 Outbox Event Publisher Contract
`OutboxEventPublisher.java` in `com.seatflow.payment.messaging.producer`:

```java
package com.seatflow.payment.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {

    private static final int MAX_RETRY_COUNT = 5;
    private static final int SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.publisher.topic:" + EventTopics.PAYMENT_EVENTS + "}")
    private String topic = EventTopics.PAYMENT_EVENTS;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findUnpublishedForUpdate(MAX_RETRY_COUNT, batchSize);
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found unpublished payment outbox events to publish. count={}", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(
                        topic,
                        event.getAggregateId().toString(),
                        event.getPayload()
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                int updated = outboxEventRepository.markPublished(event.getId(), Instant.now());
                if (updated > 0) {
                    log.info("Payment outbox event published successfully. outboxEventId={}, aggregateId={}, eventType={}, topic={}",
                            event.getId(), event.getAggregateId(), event.getEventType(), topic);
                }
            } catch (Exception ex) {
                int retryUpdated = outboxEventRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (retryUpdated == 0) {
                    log.error("Payment outbox event exceeded max retry limit ({}) or was already published. outboxEventId={}, eventType={}, aggregateId={}",
                            MAX_RETRY_COUNT, event.getId(), event.getEventType(), event.getAggregateId(), ex);
                } else {
                    log.warn("Failed to publish payment outbox event, retry incremented. outboxEventId={}, eventType={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getRetryCount() + 1, ex);
                }
            }
        }
    }
}
```

---

### 4.3 Unit Test Contract
`OutboxEventPublisherTest` uses `@ExtendWith(MockitoExtension.class)`:
- Verifies that an empty unpublished list triggers zero Kafka sends.
- Verifies that successful future delivery calls `markPublished` and records successful log.
- Verifies that broker timeout/exception calls `incrementRetryCount`.
- Verifies that max retry event is skipped and logged as ERROR.

---

### 4.4 Integration Test Contract
`PaymentServiceIntegrationTest`:
- Annotated with `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Starts static `PostgreSQLContainer<>("postgres:16-alpine")` and wires datasource via `@DynamicPropertySource`.
- Mocks `KafkaTemplate<String, String>` using `@MockitoBean` returning a completed `SendResult`.
- Mocks `ReservationServiceClient` returning an active reservation with `status = "PENDING"`.
- Mocks `StripePaymentGateway` returning a successful `StripeIntentResult`.
- Test Lifecycle:
  1. Calls `paymentService.createPaymentIntent(...)`.
  2. Asserts `Payment` entity exists with status `INITIATED`.
  3. Simulates Stripe webhook event invocation `stripeWebhookService.handleWebhookEvent(...)` for `payment_intent.succeeded`.
  4. Asserts `Payment` status is updated to `SUCCESS`.
  5. Queries `outbox_events` and asserts an unpublished `PaymentCompleted` record exists.
  6. Manually triggers `outboxEventPublisher.publishPendingEvents()`.
  7. Asserts `KafkaTemplate` received message on topic `seatflow.payment.events` with `key = paymentId`.
  8. Asserts `outbox_events` record has `published_at IS NOT NULL`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p05-005-outbox-event-publisher-and-kafka-producer` from `develop`.
2. Implement `KafkaProducerConfig` for JSON String Kafka messages.
3. Implement `OutboxEventPublisher` with lock-safe queries and timeout handling.
4. Update runtime YAML profiles with producer settings.
5. Write Mockito unit test `OutboxEventPublisherTest`.
6. Write end-to-end integration test `PaymentServiceIntegrationTest`.
7. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/payment-service -Dtest=OutboxEventPublisherTest,PaymentServiceIntegrationTest
```

- [ ] Outbox publisher polls using `SKIP LOCKED` and handles failures up to max 5 retries.
- [ ] Kafka messages contain full `EventEnvelope<DomainEvent>` JSON string keyed by `paymentId`.
- [ ] Integration test proves payment intent creation -> webhook success -> outbox persistence -> publisher execution -> published status update.
- [ ] Task file is moved to `.ai/tasks/completed/phase-05-payment-service/005-outbox-event-publisher-and-kafka-producer.md` when complete.
