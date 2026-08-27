# TASK-P06-006: Transactional Outbox Publisher & Kafka Event Producer

## 1. Task Metadata
- **Task ID:** `TASK-P06-006`
- **Git Branch:** `feat/p06-006-outbox-event-publisher-and-kafka-producer`
- **Target Module:** `backend/services/ticket-service`
- **Phase:** `Phase 06 - Ticket & QR Code Service`
- **Related Specs:** `.ai/architecture/05-messaging-and-outbox.md` (Sections 1, 2.1, 2.2, 3), `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-004-stripe-tax-and-tax-inclusive-pricing.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the Kafka producer configuration, domain event definition for `TicketIssuedEvent`, and the multi-instance safe background Outbox polling publisher (`TicketOutboxPublisher`). This ensures all digital ticket creations are reliably published to Kafka topic `seatflow.ticket.events` wrapped in `EventEnvelope<T>` with at-least-once delivery guarantees and zero dual-write hazards.

### Critical Invariants to Enforce:
- [ ] **Universal Event Envelope (`EventEnvelope<T>`):** All messages sent to `seatflow.ticket.events` must be wrapped in `EventEnvelope<TicketIssuedEvent>` from `common-events` with metadata (`eventId`, `eventType`, `occurredAt`, `correlationId`, `aggregateId`, `version`).
- [ ] **Partition Key Consistency:** The Kafka message key must be set to `aggregateId` (the `ticketId` as string) to preserve strict in-order delivery per ticket entity.
- [ ] **Retry Ceiling & Dead-Letter Safety:** If publishing fails due to broker unavailability, increment `retry_count` in `outbox_events`. The database check constraint `chk_ticket_outbox_retry` enforces a ceiling of 5 retries.
- [ ] **Trace Context Propagation:** Inject the current `correlationId` into the envelope using `CorrelationContext.getCorrelationId()`.
- [ ] **Fiscal Breakdown in Event Payload (ADR-004):** `TicketIssuedEvent` must include `price`, `taxAmount`, and `netAmount` so `notification-service` can render the full fiscal breakdown in customer confirmation emails.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/messaging/event/TicketIssuedEvent.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/config/KafkaProducerConfig.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/messaging/producer/TicketOutboxPublisher.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/messaging/producer/TicketOutboxPublisherIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Ticket Issued Domain Event Record
`messaging/event/TicketIssuedEvent.java`:
```java
package com.seatflow.ticket.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event published when a ticket is successfully issued")
public record TicketIssuedEvent(
    UUID ticketId,
    UUID reservationId,
    UUID userId,              // Nullable for guest purchases (ADR-001)
    String customerEmail,
    String attendeeName,
    UUID eventId,
    UUID seatId,
    BigDecimal price,         // Gross total price
    BigDecimal taxAmount,     // Tax / VAT portion (ADR-004)
    BigDecimal netAmount,     // Net base price (ADR-004)
    String ticketCode,
    String qrCodeData,
    Instant occurredAt
) implements DomainEvent {}
```

---

### 4.2 Kafka Producer Configuration
`config/KafkaProducerConfig.java`:
```java
package com.seatflow.ticket.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
```

---

### 4.3 Outbox Event Publisher Implementation
`messaging/producer/TicketOutboxPublisher.java`:
```java
package com.seatflow.ticket.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.ticket.messaging.event.TicketIssuedEvent;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketOutboxPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 5L;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Polling unpublished outbox events: count={}", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                Object payload = deserializePayload(event);
                String correlationId = CorrelationContext.getCorrelationId();
                if (correlationId == null || correlationId.isBlank()) {
                    correlationId = "outbox-" + UUID.randomUUID();
                }

                EventEnvelope<Object> envelope = new EventEnvelope<>(
                        UUID.randomUUID().toString(),
                        event.getEventType(),
                        event.getCreatedAt(),
                        correlationId,
                        null,
                        event.getAggregateId().toString(),
                        1,
                        payload
                );

                CompletableFuture<SendResult<String, Object>> sendFuture = kafkaTemplate.send(
                        EventTopics.TICKET_EVENTS,
                        event.getAggregateId().toString(),
                        envelope
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                event.setPublishedAt(Instant.now());
                outboxRepository.save(event);

                log.info("Ticket outbox event published successfully. topic={}, aggregateId={}, eventType={}",
                        EventTopics.TICKET_EVENTS, event.getAggregateId(), event.getEventType());

            } catch (Exception ex) {
                log.error("Error publishing ticket outbox event. aggregateId={}, eventType={}, retryCount={}",
                        event.getAggregateId(), event.getEventType(), event.getRetryCount(), ex);

                event.setRetryCount(event.getRetryCount() + 1);
                outboxRepository.save(event);
            }
        }
    }

    private Object deserializePayload(OutboxEvent event) throws Exception {
        if ("TicketIssued".equals(event.getEventType())) {
            return objectMapper.readValue(event.getPayload(), TicketIssuedEvent.class);
        }
        return objectMapper.readTree(event.getPayload());
    }
}
```

---

### 4.4 Outbox Publisher Integration Test Contract
`messaging/producer/TicketOutboxPublisherIntegrationTest`:
- Uses `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Configures static `PostgreSQLContainer` and `KafkaContainer`.
- Creates a dedicated test consumer listening on topic `seatflow.ticket.events`.
- Seeds a `TicketIssued` record in `outbox_events` table with sample `TicketIssuedEvent` JSON payload.
- Invokes `ticketOutboxPublisher.publishPendingEvents()`.
- Verifies:
  1. Test consumer receives message within 10 seconds.
  2. Deserialized `EventEnvelope` has `eventType = "TicketIssued"`, aggregateId matching test ticket ID.
  3. Deserialized payload contains correct `ticketCode`, `price`, `taxAmount`, and `netAmount`.
  4. Database record in `outbox_events` has `published_at IS NOT NULL`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p06-006-outbox-event-publisher-and-kafka-producer` from `develop`.
2. Implement `TicketIssuedEvent.java` in `messaging/event/` implementing `DomainEvent`.
3. Configure `KafkaProducerConfig.java` in `config/`.
4. Implement `TicketOutboxPublisher.java` in `messaging/producer/` with scheduled batch polling, deserialization, envelope wrapping, and failure retry handling.
5. Write `TicketOutboxPublisherIntegrationTest.java` using Testcontainers PostgreSQL and Kafka.
6. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/ticket-service -Dtest=TicketOutboxPublisherIntegrationTest
```

- [ ] `KafkaProducerConfig` and `TicketOutboxPublisher` compile cleanly.
- [ ] Integration test passes against PostgreSQL 16 and Kafka Testcontainers.
- [ ] Outbox publisher reliably delivers `EventEnvelope<TicketIssuedEvent>` to `seatflow.ticket.events`.
- [ ] Published outbox rows are updated with timestamp and retries are tracked.
- [ ] Task file is moved to `.ai/tasks/completed/phase-06-ticket-service/006-outbox-event-publisher-and-kafka-producer.md` when complete.
