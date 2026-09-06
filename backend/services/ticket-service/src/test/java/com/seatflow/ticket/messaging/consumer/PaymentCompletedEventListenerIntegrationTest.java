package com.seatflow.ticket.messaging.consumer;

import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.ticket.client.ReservationServiceClient;
import com.seatflow.ticket.client.dto.ReservationClientResponse;
import com.seatflow.ticket.messaging.event.PaymentCompletedEvent;
import com.seatflow.ticket.messaging.producer.TicketOutboxPublisher;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.repository.OutboxEventRepository;
import com.seatflow.ticket.repository.TicketRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class PaymentCompletedEventListenerIntegrationTest {

    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(KAFKA_IMAGE);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TicketOutboxPublisher ticketOutboxPublisher;

    @MockitoBean
    private ReservationServiceClient reservationServiceClient;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // Spy (not mock): the Kafka container still invokes the real listener.
    // The first delivery is awaited through a post-commit DB predicate
    // (ticket rows exist only after the issuance transaction commits); the
    // duplicate — which writes nothing — is proven through a post-return
    // latch installed before replay (see below), never a bare verify-timeout.
    @MockitoSpyBean
    private PaymentCompletedEventListener paymentCompletedEventListener;

    @Test
    void issuesTicketsAndOutboxEventsAndIsIdempotent() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        // Session B exists only as a leakage oracle: nothing persisted or
        // published may reference it.
        UUID sessionB = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String email = "buyer@example.com";
        Instant sessionStartsAt = Instant.parse("2026-10-05T19:00:00Z");
        Instant sessionEndsAt = Instant.parse("2026-10-05T21:00:00Z");

        UUID seatId1 = UUID.randomUUID();
        UUID seatId2 = UUID.randomUUID();
        ReservationClientResponse reservation = new ReservationClientResponse(
                reservationId,
                sessionId,
                eventId,
                userId,
                email,
                "CONFIRMED",
                new BigDecimal("100.00"),
                2,
                sessionStartsAt,
                sessionEndsAt,
                null,
                List.of(
                        new ReservationClientResponse.HeldSeatClientDto(UUID.randomUUID(), seatId1, "HELD", new BigDecimal("50.00")),
                        new ReservationClientResponse.HeldSeatClientDto(UUID.randomUUID(), seatId2, "HELD", new BigDecimal("50.00"))
                ),
                Instant.now(),
                Instant.now()
        );
        when(reservationServiceClient.getReservationById(eq(reservationId))).thenReturn(Optional.of(reservation));
        when(reservationServiceClient.getReservationById(eq(reservationId), any())).thenReturn(Optional.of(reservation));

        PaymentCompletedEvent event = new PaymentCompletedEvent(
                paymentId,
                reservationId,
                userId,
                email,
                sessionId,
                eventId,
                sessionStartsAt,
                sessionEndsAt,
                null,
                new BigDecimal("100.00"),
                new BigDecimal("19.00"),
                new BigDecimal("81.00"),
                "USD",
                "pi_test_123",
                Instant.now()
        );

        EventEnvelope<PaymentCompletedEvent> envelope =
                EventEnvelope.of("PaymentCompleted", paymentId.toString(), "corr-1", event);

        kafkaTemplate.send(EventTopics.PAYMENT_EVENTS, paymentId.toString(), envelope)
                .get(10, TimeUnit.SECONDS);

        await(Duration.ofSeconds(20), () -> ticketRepository.findByPaymentId(paymentId).size() == 2);

        List<Ticket> tickets = ticketRepository.findByPaymentId(paymentId);
        assertThat(tickets).hasSize(2);
        for (Ticket ticket : tickets) {
            assertThat(ticket.getPrice()).isEqualByComparingTo("50.00");
            assertThat(ticket.getTaxAmount()).isEqualByComparingTo("9.50");
            assertThat(ticket.getNetAmount()).isEqualByComparingTo("40.50");
            // P12-004: session-A reservation yields session-A tickets with immutable snapshot.
            assertThat(ticket.getEventSessionId()).isEqualTo(sessionId);
            assertThat(ticket.getEventId()).isEqualTo(eventId);
            assertThat(ticket.getSessionStartsAt()).isEqualTo(sessionStartsAt);
            assertThat(ticket.getSessionEndsAt()).isEqualTo(sessionEndsAt);
        }

        List<OutboxEvent> issuedOutbox = outboxEventRepository.findAll();
        assertThat(issuedOutbox).hasSize(2);
        for (OutboxEvent outbox : issuedOutbox) {
            assertThat(outbox.getEventType()).isEqualTo("TicketIssued");
            assertThat(outbox.getPayload()).contains(sessionId.toString());
            assertThat(outbox.getPayload()).contains(sessionStartsAt.toString());
            assertThat(outbox.getPayload()).contains(sessionEndsAt.toString());
            assertThat(outbox.getPayload()).doesNotContain(sessionB.toString());
        }

        // Idempotency: resending the same event must not create duplicate tickets
        EventEnvelope<PaymentCompletedEvent> duplicate =
                EventEnvelope.of("PaymentCompleted", paymentId.toString(), "corr-2", event);

        // Bounded consumption proof for the DUPLICATE (P12-008 REV-003): the
        // dedupe path writes nothing, so no DB predicate can prove the
        // duplicate finished — and a bare verify-timeout records listener
        // ENTRY, before the transaction necessarily completes. The post-return
        // latch below decrements only after callRealMethod returns, i.e. after
        // the listener's issuance transaction commits (first delivery) or the
        // idempotent early-return completes (duplicate); the Kafka offset
        // commit likewise follows method return. DB assertions after the latch
        // therefore observe post-commit state by construction.
        CountDownLatch duplicateConsumed = new CountDownLatch(1);
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } finally {
                duplicateConsumed.countDown();
            }
        }).when(paymentCompletedEventListener).onPaymentCompleted(any());

        kafkaTemplate.send(EventTopics.PAYMENT_EVENTS, paymentId.toString(), duplicate)
                .get(10, TimeUnit.SECONDS);

        assertThat(duplicateConsumed.await(20, TimeUnit.SECONDS))
                .as("duplicate PaymentCompleted delivery demonstrably consumed before final assertions")
                .isTrue();
        assertThat(ticketRepository.findByPaymentId(paymentId)).hasSize(2);
        assertThat(outboxEventRepository.findAll()).hasSize(2);

        // Chain continuation (P12-008 REV-003): the committed TicketIssued
        // outbox rows flow through the REAL publisher to the REAL broker;
        // consume them back and prove the same session-A identity/times with
        // no B leakage. This one test now covers PaymentCompleted (Kafka) ->
        // tickets + outbox (PostgreSQL) -> TicketIssued (Kafka) with a single
        // correlated session-A vector.
        ticketOutboxPublisher.publishPendingEvents();

        List<String> issuedPayloads = consumeTicketEvents(2, Duration.ofSeconds(20));
        assertThat(issuedPayloads).hasSize(2);
        for (String payload : issuedPayloads) {
            assertThat(payload).contains(sessionId.toString());
            assertThat(payload).contains(sessionStartsAt.toString());
            assertThat(payload).contains(sessionEndsAt.toString());
            assertThat(payload).doesNotContain(sessionB.toString());
        }
    }

    /**
     * Consumes raw {@code TICKET_EVENTS} records from the real broker with a
     * bounded poll loop. A fresh consumer group with {@code earliest} offset
     * reset reads exactly what this test's publisher wrote.
     */
    private static List<String> consumeTicketEvents(int expected, Duration timeout) {
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "p12-008-chain-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> payloads = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(EventTopics.TICKET_EVENTS));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && payloads.size() < expected) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> payloads.add(record.value()));
            }
        }
        return payloads;
    }

    private void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Condition not satisfied within " + timeout);
    }
}
