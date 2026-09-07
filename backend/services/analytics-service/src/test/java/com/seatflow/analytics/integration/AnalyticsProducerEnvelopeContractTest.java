package com.seatflow.analytics.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatflow.analytics.messaging.AnalyticsEventDispatcher;
import com.seatflow.analytics.messaging.AnalyticsEventValidationException;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionEventProcessor;
import com.seatflow.analytics.repository.AnalyticsPaymentFactRepository;
import com.seatflow.analytics.repository.AnalyticsReservationFactRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketRevocationFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.DailyRevenueMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.repository.ProcessedEventRepository;
import com.seatflow.analytics.support.AnalyticsProjectionTestConfig;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REV-001 producer-to-envelope contract bridge (TASK-P14-007 outcome B).
 *
 * <p>Golden JSON envelopes below are shaped exactly like real producer outbox serialization:
 * the root mirrors {@code objectMapper.writeValueAsString(EventEnvelope.of(...))} as stored by
 * {@code ReservationServiceImpl}/{@code StripeWebhookServiceImpl}/{@code PaymentServiceImpl}/
 * {@code TicketServiceImpl}/{@code EventServiceImpl}, and each payload carries the full field
 * set of the cited producer record (money as JSON numbers, instants as ISO-8601 strings, UUIDs
 * as strings, nullable guest fields explicit). Each envelope is driven through the exact
 * analytics listener boundary ({@link AnalyticsEventDispatcher#parseAndValidate} +
 * {@link ProjectionEventProcessor#process}).
 *
 * <p>This fails when a producer removes/renames a field analytics requires (companion
 * producer-side field assertions in reservation/payment/ticket service tests fail first) or
 * when analytics validation drifts away from the producer shape. Families with no producer
 * yet ({@code ReservationRefunded}, {@code PaymentRefunded}, {@code TicketRevoked},
 * {@code TicketScanned}/{@code TicketValidated}) are intentionally absent here: Phase 13 is
 * {@code PLANNED} and scanning is synchronous REST with no outbox event, so those remain
 * projection-reducer coverage until P13 producers land.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AnalyticsProjectionTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalyticsProducerEnvelopeContractTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_007_contract_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private AnalyticsEventDispatcher dispatcher;

    @Autowired
    private ProjectionEventProcessor processor;

    @Autowired
    private AnalyticsReservationFactRepository reservationFacts;

    @Autowired
    private AnalyticsPaymentFactRepository paymentFacts;

    @Autowired
    private AnalyticsTicketFactRepository ticketFacts;

    @Autowired
    private AnalyticsSessionFactRepository sessionFacts;

    @Autowired
    private EventSessionMetricRepository sessionMetrics;

    @Autowired
    private EventSessionRevenueMetricRepository sessionRevenue;

    @Autowired
    private AnalyticsTicketRevocationFactRepository revocationFacts;

    @Autowired
    private DailyOperationalMetricRepository dailyOperational;

    @Autowired
    private DailyRevenueMetricRepository dailyRevenue;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @BeforeEach
    void cleanReadModel() {
        dailyRevenue.deleteAll();
        dailyOperational.deleteAll();
        sessionRevenue.deleteAll();
        sessionMetrics.deleteAll();
        revocationFacts.deleteAll();
        ticketFacts.deleteAll();
        paymentFacts.deleteAll();
        reservationFacts.deleteAll();
        sessionFacts.deleteAll();
        processedEvents.deleteAll();
    }

    @Test
    @DisplayName("producer-shaped ReservationHeldEvent validates and projects")
    void reservationHeldProducerShapeProjects() {
        UUID reservation = UUID.fromString("c1111111-1111-4111-8111-111111111111");
        UUID session = UUID.fromString("c2222222-2222-4222-8222-222222222222");
        UUID event = UUID.fromString("c3333333-3333-4333-8333-333333333333");
        UUID seat = UUID.fromString("c4444444-4444-4444-8444-444444444444");
        Instant at = Instant.parse("2026-09-05T10:00:00Z");

        // Mirrors com.seatflow.reservation.messaging.event.ReservationHeldEvent
        // (guest path: userId null) serialized inside EventEnvelope by ReservationServiceImpl.
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservation.toString());
        payload.put("eventSessionId", session.toString());
        payload.put("eventId", event.toString());
        payload.putNull("userId");
        payload.put("customerEmail", "guest@example.com");
        payload.putArray("seatIds").add(seat.toString());
        payload.put("expiresAt", at.plusSeconds(900).toString());
        payload.put("totalAmount", new BigDecimal("30.00"));
        payload.put("occurredAt", at.toString());

        EventEnvelope<JsonNode> envelope = parseAndProject(
                root("contract-held-1", "ReservationHeldEvent", at, reservation.toString(), payload),
                EventTopics.RESERVATION_EVENTS);

        assertThat(reservationFacts.findById(reservation)).isPresent();
        assertThat(sessionMetrics.findById(session).orElseThrow().getReservationsCreated()).isOne();
        assertThat(envelope.eventType()).isEqualTo("ReservationHeldEvent");
    }

    @Test
    @DisplayName("producer-shaped ReservationConfirmedEvent validates and projects")
    void reservationConfirmedProducerShapeProjects() {
        UUID reservation = UUID.fromString("d1111111-1111-4111-8111-111111111111");
        UUID session = UUID.fromString("d2222222-2222-4222-8222-222222222222");
        UUID event = UUID.fromString("d3333333-3333-4333-8333-333333333333");
        UUID payment = UUID.fromString("d4444444-4444-4444-8444-444444444444");
        Instant at = Instant.parse("2026-09-05T10:06:00Z");

        // Mirrors com.seatflow.reservation.messaging.event.ReservationConfirmedEvent.
        holdFirst(reservation, session, event, at.minusSeconds(360));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservation.toString());
        payload.put("eventSessionId", session.toString());
        payload.put("eventId", event.toString());
        payload.putNull("userId");
        payload.put("customerEmail", "guest@example.com");
        payload.putArray("seatIds").add(UUID.fromString("d5555555-5555-4555-8555-555555555555").toString());
        payload.put("totalAmount", new BigDecimal("30.00"));
        payload.put("paymentId", payment.toString());
        payload.put("sessionStartsAt", "2026-10-05T19:00:00Z");
        payload.put("sessionEndsAt", "2026-10-05T21:00:00Z");
        payload.putNull("sessionTimezone");
        payload.put("occurredAt", at.toString());

        parseAndProject(
                root("contract-confirmed-1", "ReservationConfirmedEvent", at, reservation.toString(), payload),
                EventTopics.RESERVATION_EVENTS);

        assertThat(sessionMetrics.findById(session).orElseThrow().getReservationsConfirmed()).isOne();
    }

    @Test
    @DisplayName("producer-shaped ReservationExpiredEvent validates and projects")
    void reservationExpiredProducerShapeProjects() {
        UUID reservation = UUID.fromString("e1111111-1111-4111-8111-111111111111");
        UUID session = UUID.fromString("e2222222-2222-4222-8222-222222222222");
        UUID event = UUID.fromString("e3333333-3333-4333-8333-333333333333");
        Instant at = Instant.parse("2026-09-05T12:00:00Z");

        // Mirrors com.seatflow.reservation.messaging.event.ReservationExpiredEvent.
        holdFirst(reservation, session, event, at.minusSeconds(7200));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservation.toString());
        payload.put("eventSessionId", session.toString());
        payload.put("eventId", event.toString());
        payload.putArray("seatIds").add(UUID.fromString("e4444444-4444-4444-8444-444444444444").toString());
        payload.put("reason", "HOLD_TIMEOUT_EXCEEDED");
        payload.put("occurredAt", at.toString());

        parseAndProject(
                root("contract-expired-1", "ReservationExpiredEvent", at, reservation.toString(), payload),
                EventTopics.RESERVATION_EVENTS);

        assertThat(sessionMetrics.findById(session).orElseThrow().getReservationsExpired()).isOne();
    }

    @Test
    @DisplayName("producer-shaped PaymentCompleted validates and projects exact minor units")
    void paymentCompletedProducerShapeProjects() {
        UUID payment = UUID.fromString("f1111111-1111-4111-8111-111111111111");
        UUID reservation = UUID.fromString("f2222222-2222-4222-8222-222222222222");
        UUID session = UUID.fromString("f3333333-3333-4333-8333-333333333333");
        UUID event = UUID.fromString("f4444444-4444-4444-8444-444444444444");
        Instant at = Instant.parse("2026-09-05T10:05:00Z");

        // Mirrors com.seatflow.payment.messaging.event.PaymentCompletedEvent as written by
        // StripeWebhookServiceImpl: numeric major-unit money, ISO instants, nullable guest userId.
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentId", payment.toString());
        payload.put("reservationId", reservation.toString());
        payload.putNull("userId");
        payload.put("customerEmail", "cust@example.com");
        payload.put("eventSessionId", session.toString());
        payload.put("eventId", event.toString());
        payload.put("sessionStartsAt", "2026-10-05T19:00:00Z");
        payload.put("sessionEndsAt", "2026-10-05T21:00:00Z");
        payload.putNull("sessionTimezone");
        payload.put("amount", new BigDecimal("10.00"));
        payload.put("taxAmount", new BigDecimal("2.00"));
        payload.put("netAmount", new BigDecimal("8.00"));
        payload.put("currency", "USD");
        payload.put("stripePaymentId", "pi_contract_123");
        payload.put("occurredAt", at.toString());

        parseAndProject(
                root("contract-pay-1", "PaymentCompleted", at, payment.toString(), payload),
                EventTopics.PAYMENT_EVENTS);

        assertThat(paymentFacts.findById(payment)).isPresent();
        var revenue = sessionRevenue.findByEventSessionId(session);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().getCurrency()).isEqualTo("USD");
        assertThat(revenue.getFirst().getGrossRevenueMinor()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("producer-shaped PaymentFailedEvent validates and projects failure evidence")
    void paymentFailedProducerShapeProjects() {
        UUID payment = UUID.fromString("a7777777-7777-4777-8777-777777777777");
        UUID reservation = UUID.fromString("a8888888-8888-4888-8888-888888888888");
        UUID session = UUID.fromString("a9999999-9999-4999-8999-999999999999");
        UUID event = UUID.fromString("b0000000-0000-4000-8000-000000000000");
        Instant at = Instant.parse("2026-09-06T13:05:00Z");

        // Mirrors com.seatflow.payment.messaging.event.PaymentFailedEvent.
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentId", payment.toString());
        payload.put("reservationId", reservation.toString());
        payload.putNull("userId");
        payload.put("customerEmail", "cust@example.com");
        payload.put("eventSessionId", session.toString());
        payload.put("eventId", event.toString());
        payload.put("sessionStartsAt", "2026-10-05T19:00:00Z");
        payload.put("sessionEndsAt", "2026-10-05T21:00:00Z");
        payload.putNull("sessionTimezone");
        payload.put("amount", new BigDecimal("10.00"));
        payload.put("currency", "USD");
        payload.put("stripePaymentId", "pi_contract_fail");
        payload.put("failureReason", "card_declined");
        payload.put("occurredAt", at.toString());

        parseAndProject(
                root("contract-payfail-1", "PaymentFailed", at, payment.toString(), payload),
                EventTopics.PAYMENT_EVENTS);

        assertThat(sessionMetrics.findById(session).orElseThrow().getPaymentsWithFailure()).isOne();
        assertThat(sessionRevenue.findByEventSessionId(session)).isEmpty();
    }

    @Test
    @DisplayName("producer-shaped TicketIssued validates and projects")
    void ticketIssuedProducerShapeProjects() {
        UUID ticket = UUID.fromString("b1111111-1111-4111-8111-111111111111");
        UUID reservation = UUID.fromString("b2222222-2222-4222-8222-222222222222");
        UUID session = UUID.fromString("b3333333-3333-4333-8333-333333333333");
        UUID event = UUID.fromString("b4444444-4444-4444-8444-444444444444");
        Instant at = Instant.parse("2026-09-05T10:10:00Z");

        // Mirrors com.seatflow.ticket.messaging.event.TicketIssuedEvent as written by
        // TicketServiceImpl (guest path: userId null).
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("ticketId", ticket.toString());
        payload.put("reservationId", reservation.toString());
        payload.putNull("userId");
        payload.put("customerEmail", "attendee@example.com");
        payload.put("attendeeName", "Alice Attendee");
        payload.put("eventSessionId", session.toString());
        payload.put("eventId", event.toString());
        payload.put("sessionStartsAt", "2026-10-05T19:00:00Z");
        payload.put("sessionEndsAt", "2026-10-05T21:00:00Z");
        payload.putNull("sessionTimezone");
        payload.put("seatId", UUID.fromString("b5555555-5555-4555-8555-555555555555").toString());
        payload.put("price", new BigDecimal("120.00"));
        payload.put("taxAmount", new BigDecimal("20.00"));
        payload.put("netAmount", new BigDecimal("100.00"));
        payload.put("ticketCode", "SF-TKT-CONTRACT01");
        payload.put("qrCodeData", "https://seatflow.app/tickets/guest/SF-TKT-CONTRACT01");
        payload.put("occurredAt", at.toString());

        parseAndProject(
                root("contract-issued-1", "TicketIssued", at, ticket.toString(), payload),
                EventTopics.TICKET_EVENTS);

        assertThat(ticketFacts.findById(ticket)).isPresent();
    }

    @Test
    @DisplayName("producer-shaped EVENT_PUBLISHED validates without fabricating session state")
    void lifecycleProducerShapeProjects() {
        UUID event = UUID.fromString("b6666666-6666-4666-8666-666666666666");
        Instant at = Instant.parse("2026-09-05T09:00:00Z");

        // Mirrors EventServiceImpl.publishOutbox(EVENT_PUBLISHED, ...): eventId + occurredAt.
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("eventId", event.toString());
        payload.put("occurredAt", at.toString());

        parseAndProject(
                root("contract-lifecycle-1", "EVENT_PUBLISHED", at, event.toString(), payload),
                EventTopics.EVENT_EVENTS);

        assertThat(sessionFacts.findAll()).isEmpty();
    }

    @Test
    @DisplayName("producer envelope missing a required analytics field fails strictly, never projects")
    void missingRequiredProducerFieldFailsStrictly() {
        UUID payment = UUID.fromString("b7777777-7777-4777-8777-777777777777");
        UUID reservation = UUID.fromString("b8888888-8888-4888-8888-888888888888");
        Instant at = Instant.parse("2026-09-05T10:05:00Z");

        // A producer that drops paymentId (e.g. renamed field) must fail the dispatcher
        // boundary instead of projecting unattributed money.
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservation.toString());
        payload.put("amount", new BigDecimal("10.00"));
        payload.put("currency", "USD");
        payload.put("occurredAt", at.toString());

        JsonNode broken = root("contract-broken-1", "PaymentCompleted", at, payment.toString(), payload);
        assertThatThrownBy(() -> dispatcher.parseAndValidate(broken, EventTopics.PAYMENT_EVENTS))
                .isInstanceOf(AnalyticsEventValidationException.class);
        assertThat(paymentFacts.findById(payment)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private EventEnvelope<JsonNode> parseAndProject(JsonNode root, String topic) {
        EventEnvelope<JsonNode> envelope = dispatcher.parseAndValidate(root, topic);
        ProjectionEventProcessor.Outcome outcome = processor.process(
                envelope, new ConsumerRecordMetadata(topic, 0, 0L, "contract"));
        assertThat(outcome).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        return envelope;
    }

    private void holdFirst(UUID reservation, UUID session, UUID event, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservation.toString());
        payload.put("eventSessionId", session.toString());
        payload.put("eventId", event.toString());
        payload.putArray("seatIds")
                .add(UUID.fromString("c0000000-0000-4000-8000-000000000000").toString());
        payload.put("occurredAt", at.toString());
        parseAndProject(
                root("contract-hold-" + reservation, "ReservationHeldEvent", at,
                        reservation.toString(), payload),
                EventTopics.RESERVATION_EVENTS);
    }

    /** Root shape mirrors EventEnvelope Jackson serialization (headers omitted when empty). */
    private JsonNode root(String eventId, String eventType, Instant at, String aggregateId,
            ObjectNode payload) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", eventType);
        root.put("occurredAt", at.toString());
        root.put("aggregateId", aggregateId);
        root.put("correlationId", "corr-contract");
        root.put("version", EventEnvelope.CURRENT_VERSION);
        root.set("payload", payload);
        return root;
    }
}
