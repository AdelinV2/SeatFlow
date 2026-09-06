package com.seatflow.realtime.integration;

import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.ReservationCancelledEvent;
import com.seatflow.realtime.messaging.event.ReservationConfirmedEvent;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
@Testcontainers(disabledWithoutDocker = true)
@EmbeddedKafka(
        partitions = 1,
        topics = {EventTopics.RESERVATION_EVENTS, EventTopics.TICKET_EVENTS}
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealtimeServiceIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.data.redis.ssl.enabled", () -> false);
    }

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

    private static String sessionTopic(UUID eventSessionId) {
        return "/topic/sessions/" + eventSessionId + "/seats";
    }

    @Test
    @DisplayName("Should receive HELD seat status broadcast when ReservationHeld event is published to Kafka")
    void testReservationHeldEvent_BroadcastsToStompClient() throws Exception {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        Instant expiresAt = Instant.now().plusSeconds(900);

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> messageQueue = new LinkedBlockingQueue<>();
        testClientHelper.subscribe(session, sessionTopic(eventSessionId), messageQueue);

        // Allow subscription propagation
        Thread.sleep(500);

        ReservationHeldEvent payload = new ReservationHeldEvent(
                reservationId,
                eventSessionId,
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
        assertEquals(eventSessionId, received.eventSessionId());
        assertEquals(eventId, received.eventId());
        assertEquals(seatIds, received.seatIds());
        assertEquals(SeatStatus.HELD, received.status());
        assertNotNull(received.holdExpiresAt());
    }

    @Test
    @DisplayName("Should receive AVAILABLE seat status broadcast when ReservationExpired event is published to Kafka")
    void testReservationExpiredEvent_BroadcastsToStompClient() throws Exception {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID());

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> messageQueue = new LinkedBlockingQueue<>();
        testClientHelper.subscribe(session, sessionTopic(eventSessionId), messageQueue);

        Thread.sleep(500);

        ReservationExpiredEvent payload = new ReservationExpiredEvent(
                reservationId,
                eventSessionId,
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
        assertEquals(eventSessionId, received.eventSessionId());
        assertEquals(seatIds, received.seatIds());
        assertEquals(SeatStatus.AVAILABLE, received.status());
        assertNull(received.holdExpiresAt());
    }

    @Test
    @DisplayName("Should receive AVAILABLE seat status broadcast when ReservationCancelled event is published to Kafka")
    void testReservationCancelledEvent_BroadcastsToStompClient() throws Exception {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> messageQueue = new LinkedBlockingQueue<>();
        testClientHelper.subscribe(session, sessionTopic(eventSessionId), messageQueue);

        Thread.sleep(500);

        ReservationCancelledEvent payload = new ReservationCancelledEvent(
                reservationId,
                eventSessionId,
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
        assertEquals(eventSessionId, received.eventSessionId());
        assertEquals(seatIds, received.seatIds());
        assertEquals(SeatStatus.AVAILABLE, received.status());
        assertNull(received.holdExpiresAt());
    }

    @Test
    @DisplayName("Should receive SOLD seat status broadcast when TicketIssued event is published to Kafka")
    void testTicketIssuedEvent_BroadcastsToStompClient() throws Exception {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> messageQueue = new LinkedBlockingQueue<>();
        testClientHelper.subscribe(session, sessionTopic(eventSessionId), messageQueue);

        Thread.sleep(500);

        TicketIssuedEvent payload = new TicketIssuedEvent(
                ticketId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "customer@seatflow.com",
                "Alex Smith",
                eventSessionId,
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
        assertEquals(eventSessionId, received.eventSessionId());
        assertEquals(List.of(seatId), received.seatIds());
        assertEquals(SeatStatus.SOLD, received.status());
        assertNull(received.holdExpiresAt());
    }

    @Test
    @DisplayName("Should isolate session topics so subscribers only receive updates for their subscribed session")
    void testTopicIsolation_SubscribersReceiveOnlyTargetSessionUpdates() throws Exception {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID reservationA = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> queueSessionA = new LinkedBlockingQueue<>();
        BlockingQueue<SeatStatusUpdateMessage> queueSessionB = new LinkedBlockingQueue<>();

        testClientHelper.subscribe(session, sessionTopic(sessionA), queueSessionA);
        testClientHelper.subscribe(session, sessionTopic(sessionB), queueSessionB);

        Thread.sleep(500);

        // Publish event for Session A
        ReservationHeldEvent payloadA = new ReservationHeldEvent(
                reservationA,
                sessionA,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a@seatflow.com",
                List.of(UUID.randomUUID()),
                Instant.now().plusSeconds(900),
                BigDecimal.valueOf(50.00),
                Instant.now()
        );
        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationA.toString(),
                EventEnvelope.of("ReservationHeld", reservationA.toString(), "corr-a", payloadA));

        // Publish event for Session B
        ReservationHeldEvent payloadB = new ReservationHeldEvent(
                reservationB,
                sessionB,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "b@seatflow.com",
                List.of(UUID.randomUUID()),
                Instant.now().plusSeconds(900),
                BigDecimal.valueOf(50.00),
                Instant.now()
        );
        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationB.toString(),
                EventEnvelope.of("ReservationHeld", reservationB.toString(), "corr-b", payloadB));

        SeatStatusUpdateMessage msgA = queueSessionA.poll(10, TimeUnit.SECONDS);
        SeatStatusUpdateMessage msgB = queueSessionB.poll(10, TimeUnit.SECONDS);

        assertNotNull(msgA);
        assertNotNull(msgB);
        assertEquals(sessionA, msgA.eventSessionId());
        assertEquals(sessionB, msgB.eventSessionId());
        assertNull(queueSessionA.poll(1, TimeUnit.SECONDS), "Queue A should receive no extra messages");
        assertNull(queueSessionB.poll(1, TimeUnit.SECONDS), "Queue B should receive no extra messages");
    }

    @Test
    @DisplayName("Same seat UUID in two sessions of one event does not collide across session topics")
    void testSessionIsolation_SameSeatUuidAcrossSessions_DoesNotCollide() throws Exception {
        UUID sharedEventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID sharedSeatId = UUID.randomUUID();

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> queueSessionA = new LinkedBlockingQueue<>();
        BlockingQueue<SeatStatusUpdateMessage> queueSessionB = new LinkedBlockingQueue<>();

        testClientHelper.subscribe(session, sessionTopic(sessionA), queueSessionA);
        testClientHelper.subscribe(session, sessionTopic(sessionB), queueSessionB);

        Thread.sleep(500);

        UUID reservationA = UUID.randomUUID();
        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationA.toString(),
                EventEnvelope.of("ReservationHeld", reservationA.toString(), "corr-shared-a",
                        new ReservationHeldEvent(
                                reservationA,
                                sessionA,
                                sharedEventId,
                                UUID.randomUUID(),
                                "a@seatflow.com",
                                List.of(sharedSeatId),
                                Instant.now().plusSeconds(900),
                                BigDecimal.valueOf(50.00),
                                Instant.now())));

        UUID reservationB = UUID.randomUUID();
        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationB.toString(),
                EventEnvelope.of("ReservationConfirmed", reservationB.toString(), "corr-shared-b",
                        new ReservationConfirmedEvent(
                                reservationB,
                                sessionB,
                                sharedEventId,
                                UUID.randomUUID(),
                                "b@seatflow.com",
                                List.of(sharedSeatId),
                                BigDecimal.valueOf(50.00),
                                UUID.randomUUID(),
                                Instant.now())));

        SeatStatusUpdateMessage msgA = queueSessionA.poll(10, TimeUnit.SECONDS);
        SeatStatusUpdateMessage msgB = queueSessionB.poll(10, TimeUnit.SECONDS);

        assertNotNull(msgA);
        assertNotNull(msgB);
        assertEquals(sessionA, msgA.eventSessionId());
        assertEquals(SeatStatus.HELD, msgA.status());
        assertEquals(sessionB, msgB.eventSessionId());
        assertEquals(SeatStatus.SOLD, msgB.status());
        assertEquals(List.of(sharedSeatId), msgA.seatIds());
        assertEquals(List.of(sharedSeatId), msgB.seatIds());
        assertNull(queueSessionA.poll(1, TimeUnit.SECONDS), "Queue A should receive no extra messages");
        assertNull(queueSessionB.poll(1, TimeUnit.SECONDS), "Queue B should receive no extra messages");
    }

    @Test
    @DisplayName("Legacy event-only message without eventSessionId is never routed to any session")
    void testLegacyEventOnlyMessage_IsNotRouted() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID());

        StompSession session = testClientHelper.connect(wsUrl, new StompHeaders());
        BlockingQueue<SeatStatusUpdateMessage> legacyQueue = new LinkedBlockingQueue<>();
        testClientHelper.subscribe(session, "/topic/events/" + eventId + "/seats", legacyQueue);

        Thread.sleep(500);

        ReservationHeldEvent legacyPayload = new ReservationHeldEvent(
                reservationId,
                null,
                eventId,
                UUID.randomUUID(),
                "legacy@seatflow.com",
                seatIds,
                Instant.now().plusSeconds(900),
                BigDecimal.valueOf(50.00),
                Instant.now()
        );

        kafkaTemplate.send(EventTopics.RESERVATION_EVENTS, reservationId.toString(),
                EventEnvelope.of("ReservationHeld", reservationId.toString(), "corr-legacy", legacyPayload));

        assertNull(legacyQueue.poll(3, TimeUnit.SECONDS),
                "Legacy event-only message must never be routed to an inferred session");
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

    @Test
    @DisplayName("Should fail STOMP connect when invalid or expired Bearer token is provided")
    void testInvalidTokenConnect_FailsConnection() {
        String token = "invalid.expired.jwt.token";
        when(jwtDecoder.decode(eq(token))).thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("Token has expired"));

        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);

        assertThrows(Exception.class, () -> testClientHelper.connect(wsUrl, headers));
    }
}
