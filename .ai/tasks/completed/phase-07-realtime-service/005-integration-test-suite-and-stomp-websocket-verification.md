# TASK-P07-005: End-to-End WebSocket STOMP & Kafka Integration Test Suite

## 1. Task Metadata
- **Task ID:** `TASK-P07-005`
- **Git Branch:** `feat/p07-005-integration-test-suite-and-websocket-verification`
- **Target Module:** `backend/services/realtime-service`
- **Phase:** `Phase 07 - Realtime WebSocket Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 9: Realtime Service), `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the comprehensive end-to-end integration test suite verifying the complete asynchronous reactive pipeline of `realtime-service`. Tests bootstrap a live Spring Boot server on a random port (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) alongside `@EmbeddedKafka`, connect simulated STOMP WebSocket clients (using `WebSocketStompClient` and `StandardWebSocketClient`), subscribe to specific event topics (`/topic/events/{eventId}/seats`), publish domain events to Kafka topics, and assert that STOMP subscribers receive exact, properly deserialized real-time seat status transitions (`HELD`, `AVAILABLE`, `SOLD`).

### Critical Invariants to Enforce:
- [x] **Full-Stack End-to-End Pipeline Verification:** Verify Kafka event publication → Kafka consumer listener → `SeatStatusBroadcaster` → STOMP message broker → live connected WebSocket client session.
- [x] **Exact Status State Transitions:**
  - `ReservationHeldEvent` on `seatflow.reservation.events` delivers `SeatStatus.HELD` with matching `seatIds` and non-null `holdExpiresAt`.
  - `ReservationExpiredEvent` on `seatflow.reservation.events` delivers `SeatStatus.AVAILABLE` with null `holdExpiresAt`.
  - `ReservationCancelledEvent` on `seatflow.reservation.events` delivers `SeatStatus.AVAILABLE` with null `holdExpiresAt`.
  - `TicketIssuedEvent` on `seatflow.ticket.events` delivers `SeatStatus.SOLD` with matching `seatId`.
- [x] **Channel / Topic Event Isolation:** Verify that a client subscribed to Event `A` (`/topic/events/{eventA}/seats`) does NOT receive seat status broadcasts published for Event `B` (`/topic/events/{eventB}/seats`).
- [x] **Authenticated & Anonymous Connection Verification:** Verify that STOMP clients can connect both anonymously (per ADR-001 guest browsing) and with authenticated JWT headers.
- [x] **Deterministic Async Waiting:** Use `CompletableFuture` or `LinkedBlockingQueue` with generous timeouts (e.g. 5–10 seconds) to prevent flaky tests on CI runners while failing immediately if messages are dropped.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/integration/RealtimeServiceIntegrationTest.java`
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/integration/StompTestClientHelper.java`

---

## 4. Technical Specifications & Contracts

### 4.1 STOMP Test Client Helper (`StompTestClientHelper.java`)

```java
package com.seatflow.realtime.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class StompTestClientHelper {

    private final WebSocketStompClient stompClient;

    @SuppressWarnings("deprecation")
    public StompTestClientHelper() {
        this.stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        converter.setObjectMapper(mapper);
        this.stompClient.setMessageConverter(converter);
    }

    public StompSession connect(String url, StompHeaders connectHeaders) throws ExecutionException, InterruptedException, TimeoutException {
        WebSocketHttpHeaders wsHeaders = new WebSocketHttpHeaders();
        return stompClient.connectAsync(url, wsHeaders, connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                log.error("STOMP Test Client Exception: command={}, headers={}: {}", command, headers, exception.getMessage(), exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                log.error("STOMP Transport Error: {}", exception.getMessage(), exception);
            }
        }).get(5, TimeUnit.SECONDS);
    }

    public StompSession.Subscription subscribe(StompSession session, String topic, BlockingQueue<SeatStatusUpdateMessage> queue) {
        return session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return SeatStatusUpdateMessage.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                log.info("STOMP Test Client received frame on topic {}: payload={}", topic, payload);
                if (payload instanceof SeatStatusUpdateMessage updateMessage) {
                    queue.offer(updateMessage);
                }
            }
        });
    }

    public void stop() {
        if (stompClient != null && stompClient.isRunning()) {
            stompClient.stop();
        }
    }
}
```

---

### 4.2 Full Integration Test (`RealtimeServiceIntegrationTest.java`)

```java
package com.seatflow.realtime.integration;

import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.ReservationCancelledEvent;
import com.seatflow.realtime.messaging.event.ReservationExpiredEvent;
import com.seatflow.realtime.messaging.event.ReservationHeldEvent;
import com.seatflow.realtime.messaging.event.TicketIssuedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {EventTopics.RESERVATION_EVENTS, EventTopics.TICKET_EVENTS}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealtimeServiceIntegrationTest {

    @TestConfiguration
    static class KafkaTestProducerConfig {

        @Bean
        public ProducerFactory<String, Object> producerFactory(
                @Value("${spring.embedded.kafka.brokers:localhost:9092}") String bootstrapServers) {
            Map<String, Object> configProps = new HashMap<>();
            configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            return new DefaultKafkaProducerFactory<>(configProps);
        }

        @Bean
        public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
            return new KafkaTemplate<>(producerFactory);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private StompTestClientHelper testClientHelper;
    private String wsUrl;

    @BeforeEach
    void setUp() {
        testClientHelper = new StompTestClientHelper();
        wsUrl = "ws://localhost:" + port + "/ws";
    }

    @AfterEach
    void tearDown() {
        if (testClientHelper != null) {
            testClientHelper.stop();
        }
    }

    @Test
    @DisplayName("Should receive HELD seat status broadcast when ReservationHeld event is published to Kafka")
    void testReservationHeldEvent_BroadcastsToStompClient() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        Instant expiresAt = Instant.now().plusSeconds(900);

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> messageQueue = new LinkedBlockingQueue<>();
        String topic = "/topic/events/" + eventId + "/seats";
        testClientHelper.subscribe(session, topic, messageQueue);

        // Allow subscription propagation
        Thread.sleep(500);

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
                "corr-held-1",
                payload
        );

        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationId.toString(), envelope);

        SeatStatusUpdateMessage received = messageQueue.poll(10, TimeUnit.SECONDS);

        assertNotNull(received, "STOMP client should have received broadcast within timeout");
        assertEquals(eventId, received.eventId());
        assertEquals(seatIds, received.seatIds());
        assertEquals(SeatStatus.HELD, received.status());
        assertNotNull(received.holdExpiresAt());
    }

    @Test
    @DisplayName("Should receive AVAILABLE seat status broadcast when ReservationExpired event is published to Kafka")
    void testReservationExpiredEvent_BroadcastsToStompClient() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID());

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> messageQueue = new LinkedBlockingQueue<>();
        String topic = "/topic/events/" + eventId + "/seats";
        testClientHelper.subscribe(session, topic, messageQueue);

        Thread.sleep(500);

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
                "corr-exp-1",
                payload
        );

        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationId.toString(), envelope);

        SeatStatusUpdateMessage received = messageQueue.poll(10, TimeUnit.SECONDS);

        assertNotNull(received, "STOMP client should have received broadcast within timeout");
        assertEquals(eventId, received.eventId());
        assertEquals(seatIds, received.seatIds());
        assertEquals(SeatStatus.AVAILABLE, received.status());
        assertNull(received.holdExpiresAt());
    }

    @Test
    @DisplayName("Should receive AVAILABLE seat status broadcast when ReservationCancelled event is published to Kafka")
    void testReservationCancelledEvent_BroadcastsToStompClient() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> messageQueue = new LinkedBlockingQueue<>();
        String topic = "/topic/events/" + eventId + "/seats";
        testClientHelper.subscribe(session, topic, messageQueue);

        Thread.sleep(500);

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
                "corr-cancel-1",
                payload
        );

        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationId.toString(), envelope);

        SeatStatusUpdateMessage received = messageQueue.poll(10, TimeUnit.SECONDS);

        assertNotNull(received, "STOMP client should have received broadcast within timeout");
        assertEquals(eventId, received.eventId());
        assertEquals(seatIds, received.seatIds());
        assertEquals(SeatStatus.AVAILABLE, received.status());
        assertNull(received.holdExpiresAt());
    }

    @Test
    @DisplayName("Should receive SOLD seat status broadcast when TicketIssued event is published to Kafka")
    void testTicketIssuedEvent_BroadcastsToStompClient() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> messageQueue = new LinkedBlockingQueue<>();
        String topic = "/topic/events/" + eventId + "/seats";
        testClientHelper.subscribe(session, topic, messageQueue);

        Thread.sleep(500);

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

        kafkaTemplate.send(EventTopics.TICKET_EVENTS, ticketId.toString(), envelope);

        SeatStatusUpdateMessage received = messageQueue.poll(10, TimeUnit.SECONDS);

        assertNotNull(received, "STOMP client should have received broadcast within timeout");
        assertEquals(eventId, received.eventId());
        assertEquals(List.of(seatId), received.seatIds());
        assertEquals(SeatStatus.SOLD, received.status());
        assertNull(received.holdExpiresAt());
    }

    @Test
    @DisplayName("Should isolate event topics so subscribers only receive updates for their subscribed event")
    void testTopicIsolation_SubscribersReceiveOnlyTargetEventUpdates() throws Exception {
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        UUID reservationA = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> queueEventA = new LinkedBlockingQueue<>();
        BlockingQueue<SeatStatusUpdateMessage> queueEventB = new LinkedBlockingQueue<>();

        testClientHelper.subscribe(session, "/topic/events/" + eventA + "/seats", queueEventA);
        testClientHelper.subscribe(session, "/topic/events/" + eventB + "/seats", queueEventB);

        Thread.sleep(500);

        // Publish event for Event A
        ReservationHeldEvent payloadA = new ReservationHeldEvent(
                reservationA,
                eventA,
                UUID.randomUUID(),
                "a@seatflow.com",
                List.of(UUID.randomUUID()),
                Instant.now().plusSeconds(900),
                BigDecimal.valueOf(50.00),
                Instant.now()
        );
        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationA.toString(),
                EventEnvelope.of("ReservationHeld", reservationA.toString(), "corr-a", payloadA));

        // Publish event for Event B
        ReservationHeldEvent payloadB = new ReservationHeldEvent(
                reservationB,
                eventB,
                UUID.randomUUID(),
                "b@seatflow.com",
                List.of(UUID.randomUUID()),
                Instant.now().plusSeconds(900),
                BigDecimal.valueOf(50.00),
                Instant.now()
        );
        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationB.toString(),
                EventEnvelope.of("ReservationHeld", reservationB.toString(), "corr-b", payloadB));

        SeatStatusUpdateMessage msgA = queueEventA.poll(10, TimeUnit.SECONDS);
        SeatStatusUpdateMessage msgB = queueEventB.poll(10, TimeUnit.SECONDS);

        assertNotNull(msgA);
        assertNotNull(msgB);
        assertEquals(eventA, msgA.eventId());
        assertEquals(eventB, msgB.eventId());
        assertNull(queueEventA.poll(1, TimeUnit.SECONDS), "Queue A should receive no extra messages");
        assertNull(queueEventB.poll(1, TimeUnit.SECONDS), "Queue B should receive no extra messages");
    }

    @Test
    @DisplayName("Should successfully authenticate STOMP CONNECT frame when valid Bearer token is provided")
    void testAuthenticatedConnect_EstablishesSession() throws Exception {
        String token = "valid.jwt.token";
        Jwt mockJwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "authenticated-user-123", "email", "auth@seatflow.com")
        );
        when(jwtDecoder.decode(eq(token))).thenReturn(mockJwt);

        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);

        StompSession session = testClientHelper.connect(wsUrl, headers);
        assertTrue(session.isConnected(), "STOMP session should be connected with valid JWT");
        session.disconnect();
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Test Client Harness:** Create `com.seatflow.realtime.integration.StompTestClientHelper` setting up `WebSocketStompClient`, `MappingJackson2MessageConverter` with `JavaTimeModule`, connection helpers, and subscription queue integration.
2. **Integration Test Suite:** Create `com.seatflow.realtime.integration.RealtimeServiceIntegrationTest` with `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `@EmbeddedKafka`.
3. **Write Verification Scenarios:**
   - `testReservationHeldEvent_BroadcastsToStompClient`: Verify `ReservationHeld` -> `HELD` with hold expiration.
   - `testReservationExpiredEvent_BroadcastsToStompClient`: Verify `ReservationExpired` -> `AVAILABLE`.
   - `testReservationCancelledEvent_BroadcastsToStompClient`: Verify `ReservationCancelled` -> `AVAILABLE`.
   - `testTicketIssuedEvent_BroadcastsToStompClient`: Verify `TicketIssued` -> `SOLD`.
   - `testTopicIsolation_SubscribersReceiveOnlyTargetEventUpdates`: Verify topic isolation between events.
   - `testAuthenticatedConnect_EstablishesSession`: Verify authenticated STOMP CONNECT frame.
4. **Execution & Validation:** Run Maven test command to verify the complete test suite.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl backend/services/realtime-service -Dtest=RealtimeServiceIntegrationTest
```
- [x] `RealtimeServiceIntegrationTest` executes against EmbeddedKafka and Spring Boot live WebSocket server.
- [x] End-to-end flow from Kafka publication to STOMP topic delivery is validated for `HELD`, `AVAILABLE`, and `SOLD` transitions.
- [x] Multi-event topic isolation guarantees subscribers only receive relevant event broadcasts.
- [x] Both anonymous and JWT-authenticated STOMP connections succeed.
- [x] All integration tests pass deterministically without race conditions or timeouts.
- [x] Task file is moved to `.ai/tasks/completed/phase-07-realtime-service/005-integration-test-suite-and-stomp-websocket-verification.md`.
