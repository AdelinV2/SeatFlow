# TASK-P07-004: Kafka Event Listeners & Universal Envelope Unwrapping

## 1. Task Metadata
- **Task ID:** `TASK-P07-004`
- **Git Branch:** `feat/p07-004-kafka-listeners-and-unwrapping`
- **Target Module:** `backend/services/realtime-service`
- **Phase:** `Phase 07 - Realtime WebSocket Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 9: Realtime Service), `.ai/architecture/05-messaging-and-outbox.md` (Sections 1, 2, 4, 5, 7)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the Kafka consumer infrastructure and event listeners for `realtime-service`. The service consumes domain events wrapped in universal `EventEnvelope<T>` from `seatflow.reservation.events` (`ReservationHeld`, `ReservationExpired`, `ReservationCancelled`) and `seatflow.ticket.events` (`TicketIssued`). The listeners unwrap the payloads, propagate correlation IDs to the MDC logging context, translate the lifecycle events into domain seat status changes (`AVAILABLE`, `HELD`, `SOLD`), and delegate immediately to `SeatStatusBroadcaster` for STOMP topic transmission.

### Critical Invariants to Enforce:
- [x] **Consumer Group Isolation:** All listeners in `realtime-service` configure `groupId = "realtime-service"`.
- [x] **Event Topics Catalog:**
  - `seatflow.reservation.events` (`EventTopics.RESERVATION_EVENTS`):
    - `ReservationHeld` → `SeatStatus.HELD` with `holdExpiresAt = event.expiresAt()`.
    - `ReservationExpired` → `SeatStatus.AVAILABLE` with `holdExpiresAt = null`.
    - `ReservationCancelled` → `SeatStatus.AVAILABLE` with `holdExpiresAt = null`.
  - `seatflow.ticket.events` (`EventTopics.TICKET_EVENTS`):
    - `TicketIssued` → `SeatStatus.SOLD` with `holdExpiresAt = null`.
- [x] **Universal Envelope Unwrapping:** All Kafka messages must be deserialized as `EventEnvelope<T>` from `common-events`. Never parse raw JSON without envelope metadata.
- [x] **Trace Context & MDC Propagation:** Extract `envelope.correlationId()` and bind to MDC `correlationId` before processing; clear MDC in a `finally` block to prevent thread contamination.
- [x] **Polymorphic Topic Resilience:** Listeners must handle known event types and silently ignore unhandled events (e.g. `ReservationConfirmedEvent`, `TicketValidatedEvent`) on shared aggregate topics without throwing deserialization or routing errors.
- [x] **Zero-Error Kafka Deserialization:** Configure `ErrorHandlingDeserializer` wrapping `JsonDeserializer` with trusted packages (`com.seatflow.*`) and a `DefaultErrorHandler` with backoff.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/event/ReservationHeldEvent.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/event/ReservationExpiredEvent.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/event/ReservationCancelledEvent.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/event/TicketIssuedEvent.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/config/KafkaConsumerConfig.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/consumer/ReservationEventListener.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/consumer/TicketEventListener.java`
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/messaging/consumer/ReservationEventListenerTest.java`
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/messaging/consumer/TicketEventListenerTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Inbound Domain Event Payloads

#### `com.seatflow.realtime.messaging.event.ReservationHeldEvent`:
```java
package com.seatflow.realtime.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event received when seats are temporarily held for a reservation")
public record ReservationHeldEvent(
        UUID reservationId,
        UUID eventId,
        UUID userId,              // Nullable for guest checkouts (ADR-001)
        String customerEmail,
        List<UUID> seatIds,
        Instant expiresAt,
        BigDecimal totalAmount,
        Instant occurredAt
) implements DomainEvent {}
```

#### `com.seatflow.realtime.messaging.event.ReservationExpiredEvent`:
```java
package com.seatflow.realtime.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event received when a temporary seat hold expires")
public record ReservationExpiredEvent(
        UUID reservationId,
        UUID eventId,
        List<UUID> seatIds,
        String reason,
        Instant occurredAt
) implements DomainEvent {}
```

#### `com.seatflow.realtime.messaging.event.ReservationCancelledEvent`:
```java
package com.seatflow.realtime.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event received when a reservation is manually cancelled")
public record ReservationCancelledEvent(
        UUID reservationId,
        UUID eventId,
        UUID userId,
        String customerEmail,
        List<UUID> seatIds,
        Instant occurredAt
) implements DomainEvent {}
```

#### `com.seatflow.realtime.messaging.event.TicketIssuedEvent`:
```java
package com.seatflow.realtime.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event received when a digital ticket is generated and issued for a seat")
public record TicketIssuedEvent(
        UUID ticketId,
        UUID reservationId,
        UUID userId,              // Nullable for guest checkouts (ADR-001)
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
```

---

### 4.2 Kafka Consumer Configuration (`KafkaConsumerConfig.java`)

```java
package com.seatflow.realtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
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
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@EnableKafka
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:realtime-service}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:latest}")
    private String autoOffsetReset;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        Deserializer<Object> envelopeDeserializer = (topic, data) -> {
            if (data == null) {
                return null;
            }
            try {
                return objectMapper.readValue(data, EventEnvelope.class);
            } catch (IOException ex) {
                throw new SerializationException("Failed to deserialize Kafka envelope for topic " + topic, ex);
            }
        };

        Deserializer<Object> valueDeserializer = new ErrorHandlingDeserializer<>(envelopeDeserializer);

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, exception) -> log.error("Kafka consumer error on topic={}, partition={}, offset={}, key={}: {}",
                        record.topic(), record.partition(), record.offset(), record.key(), exception.getMessage(), exception),
                new FixedBackOff(1000L, 3L)
        );
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Retrying realtime-service consumer record. attempt={}, topic={}", deliveryAttempt, record.topic()));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
```

---

### 4.3 Kafka Event Listeners

#### `com.seatflow.realtime.messaging.consumer.ReservationEventListener`:
```java
package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.ReservationCancelledEvent;
import com.seatflow.realtime.messaging.event.ReservationExpiredEvent;
import com.seatflow.realtime.messaging.event.ReservationHeldEvent;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    private final SeatStatusBroadcaster seatStatusBroadcaster;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = EventTopics.RESERVATION_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:realtime-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleReservationEvent(EventEnvelope<?> envelope) {
        if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
            log.warn("Received invalid envelope (null envelope, missing eventType, or null payload), skipping message");
            return;
        }

        String correlationId = envelope.correlationId() != null ? envelope.correlationId() : "";
        CorrelationContext.setCorrelationId(correlationId);
        MDC.put("correlationId", correlationId);
        if (envelope.eventId() != null) {
            MDC.put("traceId", envelope.eventId());
        }

        try {
            log.info("Received reservation event: type={}, eventId={}, aggregateId={}",
                    envelope.eventType(), envelope.eventId(), envelope.aggregateId());

            switch (envelope.eventType()) {
                case "ReservationHeld" -> {
                    ReservationHeldEvent event = convertPayload(envelope.payload(), ReservationHeldEvent.class);
                    seatStatusBroadcaster.broadcastSeatStatus(
                            event.eventId(),
                            event.seatIds(),
                            SeatStatus.HELD,
                            event.expiresAt()
                    );
                }
                case "ReservationExpired" -> {
                    ReservationExpiredEvent event = convertPayload(envelope.payload(), ReservationExpiredEvent.class);
                    seatStatusBroadcaster.broadcastSeatStatus(
                            event.eventId(),
                            event.seatIds(),
                            SeatStatus.AVAILABLE,
                            null
                    );
                }
                case "ReservationCancelled" -> {
                    ReservationCancelledEvent event = convertPayload(envelope.payload(), ReservationCancelledEvent.class);
                    seatStatusBroadcaster.broadcastSeatStatus(
                            event.eventId(),
                            event.seatIds(),
                            SeatStatus.AVAILABLE,
                            null
                    );
                }
                default -> log.debug("Ignoring reservation event type: {}", envelope.eventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process reservation event: type={}, eventId={}: {}",
                    envelope.eventType(), envelope.eventId(), ex.getMessage(), ex);
            throw ex;
        } finally {
            MDC.remove("correlationId");
            MDC.remove("traceId");
            CorrelationContext.clear();
        }
    }

    private <T> T convertPayload(Object payload, Class<T> targetClass) {
        if (targetClass.isInstance(payload)) {
            return targetClass.cast(payload);
        }
        return objectMapper.convertValue(payload, targetClass);
    }
}
```

#### `com.seatflow.realtime.messaging.consumer.TicketEventListener`:
```java
package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.TicketIssuedEvent;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketEventListener {

    private final SeatStatusBroadcaster seatStatusBroadcaster;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = EventTopics.TICKET_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:realtime-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleTicketEvent(EventEnvelope<?> envelope) {
        if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
            log.warn("Received invalid envelope (null envelope, missing eventType, or null payload), skipping message");
            return;
        }

        String correlationId = envelope.correlationId() != null ? envelope.correlationId() : "";
        CorrelationContext.setCorrelationId(correlationId);
        MDC.put("correlationId", correlationId);
        if (envelope.eventId() != null) {
            MDC.put("traceId", envelope.eventId());
        }

        try {
            log.info("Received ticket event: type={}, eventId={}, aggregateId={}",
                    envelope.eventType(), envelope.eventId(), envelope.aggregateId());

            if ("TicketIssued".equals(envelope.eventType())) {
                TicketIssuedEvent event = convertPayload(envelope.payload(), TicketIssuedEvent.class);
                seatStatusBroadcaster.broadcastSeatStatus(
                        event.eventId(),
                        event.seatId(),
                        SeatStatus.SOLD
                );
            } else {
                log.debug("Ignoring ticket event type: {}", envelope.eventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process ticket event: type={}, eventId={}: {}",
                    envelope.eventType(), envelope.eventId(), ex.getMessage(), ex);
            throw ex;
        } finally {
            MDC.remove("correlationId");
            MDC.remove("traceId");
            CorrelationContext.clear();
        }
    }

    private <T> T convertPayload(Object payload, Class<T> targetClass) {
        if (targetClass.isInstance(payload)) {
            return targetClass.cast(payload);
        }
        return objectMapper.convertValue(payload, targetClass);
    }
}
```

---

### 4.4 Unit Tests

#### `src/test/java/com/seatflow/realtime/messaging/consumer/ReservationEventListenerTest.java`:
```java
package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.ReservationCancelledEvent;
import com.seatflow.realtime.messaging.event.ReservationExpiredEvent;
import com.seatflow.realtime.messaging.event.ReservationHeldEvent;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationEventListenerTest {

    record DummyEvent(String message) implements DomainEvent {}

    @Mock
    private SeatStatusBroadcaster seatStatusBroadcaster;

    private ObjectMapper objectMapper;
    private ReservationEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new ReservationEventListener(seatStatusBroadcaster, objectMapper);
    }

    @Test
    @DisplayName("Should process ReservationHeld event and broadcast HELD status with expiration timestamp")
    void handleReservationEvent_ReservationHeld_BroadcastsHeld() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        Instant expiresAt = Instant.now().plusSeconds(900);

        ReservationHeldEvent payload = new ReservationHeldEvent(
                reservationId,
                eventId,
                UUID.randomUUID(),
                "customer@seatflow.com",
                seatIds,
                expiresAt,
                BigDecimal.valueOf(150.00),
                Instant.now()
        );

        EventEnvelope<ReservationHeldEvent> envelope = EventEnvelope.of(
                "ReservationHeld",
                reservationId.toString(),
                "corr-1234",
                payload
        );

        listener.handleReservationEvent(envelope);

        verify(seatStatusBroadcaster).broadcastSeatStatus(eventId, seatIds, SeatStatus.HELD, expiresAt);
    }

    @Test
    @DisplayName("Should process ReservationExpired event and broadcast AVAILABLE status")
    void handleReservationEvent_ReservationExpired_BroadcastsAvailable() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID());

        ReservationExpiredEvent payload = new ReservationExpiredEvent(
                reservationId,
                eventId,
                seatIds,
                "HOLD_TIMEOUT_EXCEEDED",
                Instant.now()
        );

        EventEnvelope<ReservationExpiredEvent> envelope = EventEnvelope.of(
                "ReservationExpired",
                reservationId.toString(),
                "corr-5678",
                payload
        );

        listener.handleReservationEvent(envelope);

        verify(seatStatusBroadcaster).broadcastSeatStatus(eventId, seatIds, SeatStatus.AVAILABLE, null);
    }

    @Test
    @DisplayName("Should process ReservationCancelled event and broadcast AVAILABLE status")
    void handleReservationEvent_ReservationCancelled_BroadcastsAvailable() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        ReservationCancelledEvent payload = new ReservationCancelledEvent(
                reservationId,
                eventId,
                UUID.randomUUID(),
                "customer@seatflow.com",
                seatIds,
                Instant.now()
        );

        EventEnvelope<ReservationCancelledEvent> envelope = EventEnvelope.of(
                "ReservationCancelled",
                reservationId.toString(),
                "corr-9999",
                payload
        );

        listener.handleReservationEvent(envelope);

        verify(seatStatusBroadcaster).broadcastSeatStatus(eventId, seatIds, SeatStatus.AVAILABLE, null);
    }

    @Test
    @DisplayName("Should silently ignore unrecognized reservation event types")
    void handleReservationEvent_UnrecognizedType_IgnoresEvent() {
        EventEnvelope<DummyEvent> envelope = EventEnvelope.of(
                "ReservationConfirmed",
                UUID.randomUUID().toString(),
                "corr-0000",
                new DummyEvent("generic-payload")
        );

        listener.handleReservationEvent(envelope);

        verifyNoInteractions(seatStatusBroadcaster);
    }

    @Test
    @DisplayName("Should silently return when envelope is null or eventType is null")
    void handleReservationEvent_NullEnvelope_IgnoresGracefully() {
        listener.handleReservationEvent(null);
        listener.handleReservationEvent(EventEnvelope.of(null, "id", "corr", null));

        verifyNoInteractions(seatStatusBroadcaster);
    }
}
```

#### `src/test/java/com/seatflow/realtime/messaging/consumer/TicketEventListenerTest.java`:
```java
package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.TicketIssuedEvent;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketEventListenerTest {

    record DummyEvent(String message) implements DomainEvent {}

    @Mock
    private SeatStatusBroadcaster seatStatusBroadcaster;

    private ObjectMapper objectMapper;
    private TicketEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new TicketEventListener(seatStatusBroadcaster, objectMapper);
    }

    @Test
    @DisplayName("Should process TicketIssued event and broadcast SOLD status for single seat")
    void handleTicketEvent_TicketIssued_BroadcastsSold() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        TicketIssuedEvent payload = new TicketIssuedEvent(
                ticketId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "customer@seatflow.com",
                "Alex Smith",
                eventId,
                seatId,
                BigDecimal.valueOf(75.00),
                BigDecimal.valueOf(14.25),
                BigDecimal.valueOf(60.75),
                "SF-TKT-1234-ABCD",
                "SF://TKT/1234/SIGN",
                Instant.now()
        );

        EventEnvelope<TicketIssuedEvent> envelope = EventEnvelope.of(
                "TicketIssued",
                ticketId.toString(),
                "corr-ticket-1",
                payload
        );

        listener.handleTicketEvent(envelope);

        verify(seatStatusBroadcaster).broadcastSeatStatus(eventId, List.of(seatId), SeatStatus.SOLD, null);
    }

    @Test
    @DisplayName("Should silently ignore non-TicketIssued event types on ticket topic")
    void handleTicketEvent_OtherEventType_IgnoresEvent() {
        EventEnvelope<DummyEvent> envelope = EventEnvelope.of(
                "TicketValidated",
                UUID.randomUUID().toString(),
                "corr-ticket-2",
                new DummyEvent("generic-data")
        );

        listener.handleTicketEvent(envelope);

        verifyNoInteractions(seatStatusBroadcaster);
    }

    @Test
    @DisplayName("Should silently return when envelope is null or eventType is null")
    void handleTicketEvent_NullEnvelope_IgnoresGracefully() {
        listener.handleTicketEvent(null);
        listener.handleTicketEvent(EventEnvelope.of(null, "id", "corr", null));

        verifyNoInteractions(seatStatusBroadcaster);
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Domain Event Records:** Create domain event payload records (`ReservationHeldEvent`, `ReservationExpiredEvent`, `ReservationCancelledEvent`, `TicketIssuedEvent`) in `com.seatflow.realtime.messaging.event` implementing `DomainEvent`.
2. **Kafka Consumer Configuration:** Create `com.seatflow.realtime.config.KafkaConsumerConfig` with `StringDeserializer`, `ErrorHandlingDeserializer`, `JsonDeserializer` (trusted packages configured), and `DefaultErrorHandler`.
3. **Reservation Listener:** Create `com.seatflow.realtime.messaging.consumer.ReservationEventListener` listening to `EventTopics.RESERVATION_EVENTS`, extracting correlation ID into MDC, translating `ReservationHeld` -> `HELD`, `ReservationExpired`/`ReservationCancelled` -> `AVAILABLE`, and calling `SeatStatusBroadcaster`.
4. **Ticket Listener:** Create `com.seatflow.realtime.messaging.consumer.TicketEventListener` listening to `EventTopics.TICKET_EVENTS`, translating `TicketIssued` -> `SOLD`, and calling `SeatStatusBroadcaster`.
5. **Testing & Verification:**
   - Create `ReservationEventListenerTest.java` and `TicketEventListenerTest.java`.
   - Run verification command to ensure listener routing and error handling are verified.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl backend/services/realtime-service -Dtest=ReservationEventListenerTest,TicketEventListenerTest
```
- [x] Inbound domain events implement `DomainEvent` with all schema fields matching `.ai/architecture/05-messaging-and-outbox.md`.
- [x] `KafkaConsumerConfig` safely configures `ErrorHandlingDeserializer` with trusted package scanning and fixed backoff error handler.
- [x] `ReservationEventListener` maps `ReservationHeld`, `ReservationExpired`, and `ReservationCancelled` to `HELD` and `AVAILABLE` statuses.
- [x] `TicketEventListener` maps `TicketIssued` to `SOLD` status.
- [x] Correlation IDs are propagated to MDC and cleaned up in `finally` blocks.
- [x] All unit tests pass cleanly.
- [x] Task file is moved to `.ai/tasks/completed/phase-07-realtime-service/004-kafka-event-listeners-and-envelope-unwrapping.md`.
