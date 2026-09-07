package com.seatflow.analytics.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionEventProcessor;
import com.seatflow.analytics.messaging.handlers.TicketIssuedProjectionHandler;
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
import com.seatflow.analytics.support.AnalyticsCanonicalFixtures;
import com.seatflow.analytics.support.AnalyticsEnvelopeFactory;
import com.seatflow.analytics.support.AnalyticsProjectionTestConfig;
import com.seatflow.analytics.support.AnalyticsSnapshot;
import com.seatflow.common.events.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * TASK-P14-007 section 7.1-7.4 + 7.12 (partial) acceptance suite over real PostgreSQL.
 *
 * <p>Drives the full P14-002 claim path ({@link ProjectionEventProcessor} + real adapters)
 * with the same {@code EventEnvelope.eventId} redelivered, proving duplicate delivery cannot
 * inflate facts or aggregates. Broker-level retry/DLQ behavior (7.5) is proven by
 * {@code AnalyticsConsumerKafkaIntegrationTest} against a real embedded broker with the
 * production {@code DefaultErrorHandler}; this class proves the synchronous DB half of the
 * contract (claim atomicity, rollback, redelivery window) deterministically.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AnalyticsProjectionTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalyticsKafkaProjectionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_007_kafka_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @MockitoSpyBean
    private TicketIssuedProjectionHandler issuedHandler;

    @Autowired
    private ProjectionEventProcessor processor;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private AnalyticsReservationFactRepository reservationFacts;

    @Autowired
    private AnalyticsPaymentFactRepository paymentFacts;

    @Autowired
    private AnalyticsTicketFactRepository ticketFacts;

    @Autowired
    private AnalyticsTicketRevocationFactRepository revocationFacts;

    @Autowired
    private AnalyticsSessionFactRepository sessionFacts;

    @Autowired
    private EventSessionMetricRepository sessionMetrics;

    @Autowired
    private EventSessionRevenueMetricRepository sessionRevenue;

    @Autowired
    private DailyOperationalMetricRepository dailyOperational;

    @Autowired
    private DailyRevenueMetricRepository dailyRevenue;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AnalyticsSnapshot.Driver driver;

    @BeforeEach
    void cleanState() {
        reset(issuedHandler);
        driver = new AnalyticsSnapshot.Driver(processor);
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

    // ------------------------------------------------------------------
    // 7.1 Duplicate delivery: same EventEnvelope.eventId
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.1 duplicate ReservationHeld projects once with one marker")
    void duplicateReservationHeldIsNoOp() {
        EventEnvelope<JsonNode> envelope = AnalyticsEnvelopeFactory.held(
                "p14-007-dup-held", AnalyticsCanonicalFixtures.R1,
                AnalyticsCanonicalFixtures.EVENT_E1, AnalyticsCanonicalFixtures.S1,
                2, AnalyticsCanonicalFixtures.D1_HOLD);

        assertThat(driver.process(envelope)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        String first = snapshot();
        assertThat(driver.process(envelope)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);

        assertThat(markerCount("p14-007-dup-held")).isOne();
        assertThat(sessionMetrics.findById(AnalyticsCanonicalFixtures.S1).orElseThrow()
                .getReservationsCreated()).isOne();
    }

    @Test
    @DisplayName("7.1 duplicate PaymentCompleted cannot inflate gross revenue")
    void duplicatePaymentCompletedIsNoOp() {
        UUID session = UUID.fromString("d1111111-1111-4111-8111-111111111111");
        UUID reservation = UUID.fromString("d1111111-2222-4222-8222-222222222222");
        UUID payment = UUID.fromString("d1111111-3333-4333-8333-333333333333");
        Instant at = Instant.parse("2026-09-05T10:00:00Z");
        EventEnvelope<JsonNode> held = AnalyticsEnvelopeFactory.held(
                "p14-007-dup-pay-held", reservation, AnalyticsCanonicalFixtures.EVENT_E1, session,
                1, at);
        EventEnvelope<JsonNode> completed = AnalyticsEnvelopeFactory.completed(
                "p14-007-dup-pay", payment, reservation, session, AnalyticsCanonicalFixtures.EVENT_E1,
                "200.00", "RON", at.plusSeconds(60));

        assertThat(driver.process(held)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(driver.process(completed)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        String first = snapshot();
        assertThat(driver.process(completed)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);

        assertThat(markerCount("p14-007-dup-pay")).isOne();
        assertThat(sessionRevenue.findByEventSessionId(session)).hasSize(1);
        assertThat(sessionRevenue.findByEventSessionId(session).getFirst().getGrossRevenueMinor())
                .isEqualTo(20_000L);
    }

    @Test
    @DisplayName("7.1 duplicate PaymentRefunded cannot double-count the refund")
    void duplicatePaymentRefundedIsNoOp() {
        List<EventEnvelope<JsonNode>> history = AnalyticsCanonicalFixtures.s1History();
        // Replay S1 up to and including the payment refund (index 6).
        history.subList(0, 7).forEach(e ->
                assertThat(driver.process(e)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED));
        String first = snapshot();

        EventEnvelope<JsonNode> refund = history.get(6);
        assertThat(driver.process(refund)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);

        assertThat(markerCount(refund.eventId())).isOne();
        var revenue = sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S1);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().getRefundedRevenueMinor()).isEqualTo(20_000L);
        assertThat(revenue.getFirst().netRevenueMinor()).isZero();
    }

    @Test
    @DisplayName("7.1 duplicate TicketIssued cannot inflate issued counts")
    void duplicateTicketIssuedIsNoOp() {
        List<EventEnvelope<JsonNode>> history = AnalyticsCanonicalFixtures.s1History();
        history.subList(0, 4).forEach(e ->
                assertThat(driver.process(e)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED));
        String first = snapshot();

        EventEnvelope<JsonNode> issued = history.get(3);
        assertThat(driver.process(issued)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);

        assertThat(markerCount(issued.eventId())).isOne();
        assertThat(sessionMetrics.findById(AnalyticsCanonicalFixtures.S1).orElseThrow()
                .getTicketsIssued()).isOne();
    }

    @Test
    @DisplayName("7.1 duplicate accepted scan cannot inflate attendance")
    void duplicateTicketScanIsNoOp() {
        List<EventEnvelope<JsonNode>> history = AnalyticsCanonicalFixtures.s1History();
        history.subList(0, 6).forEach(e ->
                assertThat(driver.process(e)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED));
        String first = snapshot();

        EventEnvelope<JsonNode> scan = history.get(5);
        assertThat(driver.process(scan)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);

        assertThat(markerCount(scan.eventId())).isOne();
        assertThat(sessionMetrics.findById(AnalyticsCanonicalFixtures.S1).orElseThrow()
                .getTicketsScanned()).isOne();
    }

    @Test
    @DisplayName("7.1 duplicate TicketRevoked cannot inflate revocation counts")
    void duplicateTicketRevokedIsNoOp() {
        List<EventEnvelope<JsonNode>> history = AnalyticsCanonicalFixtures.s1History();
        history.forEach(e ->
                assertThat(driver.process(e)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED));
        String first = snapshot();

        EventEnvelope<JsonNode> revoked = history.get(8);
        assertThat(driver.process(revoked)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);

        assertThat(markerCount(revoked.eventId())).isOne();
        assertThat(sessionMetrics.findById(AnalyticsCanonicalFixtures.S1).orElseThrow()
                .getTicketsRevoked()).isEqualTo(2L);
    }

    @Test
    @DisplayName("7.1 duplicate delivery never poisons the consumer for subsequent records")
    void duplicateDoesNotPoisonSubsequentRecords() {
        EventEnvelope<JsonNode> first = AnalyticsEnvelopeFactory.held(
                "p14-007-poison-held", AnalyticsCanonicalFixtures.R2,
                AnalyticsCanonicalFixtures.EVENT_E1, AnalyticsCanonicalFixtures.S2,
                1, AnalyticsCanonicalFixtures.D1_HOLD);
        assertThat(driver.process(first)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(driver.process(first)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);

        EventEnvelope<JsonNode> next = AnalyticsEnvelopeFactory.expired(
                "p14-007-poison-expired", AnalyticsCanonicalFixtures.R2,
                AnalyticsCanonicalFixtures.EVENT_E1, AnalyticsCanonicalFixtures.S2,
                AnalyticsCanonicalFixtures.D1_EXPIRE);
        assertThat(driver.process(next)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(sessionMetrics.findById(AnalyticsCanonicalFixtures.S2).orElseThrow()
                .getReservationsExpired()).isOne();
    }

    // ------------------------------------------------------------------
    // 7.2 Concurrent duplicate claim against real PostgreSQL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.2 concurrent duplicate claim owns the event exactly once via ON CONFLICT")
    void concurrentDuplicateClaimsOnce() throws Exception {
        EventEnvelope<JsonNode> envelope = AnalyticsEnvelopeFactory.held(
                "p14-007-concurrent-held", UUID.fromString("e1111111-1111-4111-8111-111111111111"),
                AnalyticsCanonicalFixtures.EVENT_E1,
                UUID.fromString("e2222222-2222-4222-8222-222222222222"),
                1, Instant.parse("2026-09-05T10:00:00Z"));

        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ProjectionEventProcessor.Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("contenders did not align");
                    }
                    return processor.process(envelope, new ConsumerRecordMetadata(
                            "seatflow.reservation.events", 0, 0L, "p14-007"));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int processed = 0;
            int duplicates = 0;
            for (Future<ProjectionEventProcessor.Outcome> future : futures) {
                switch (future.get(30, TimeUnit.SECONDS)) {
                    case PROCESSED -> processed++;
                    case DUPLICATE -> duplicates++;
                    case IGNORED -> throw new IllegalStateException("unexpected IGNORED");
                }
            }
            assertThat(processed).isOne();
            assertThat(duplicates).isEqualTo(contenders - 1);
            assertThat(markerCount("p14-007-concurrent-held")).isOne();
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 7.3 Handler failure after claim rolls back the marker
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.3 transient failure after claim rolls back the marker and retry succeeds once")
    void handlerFailureRollsBackClaimAndRetrySucceeds() {
        UUID session = UUID.fromString("f1111111-1111-4111-8111-111111111111");
        UUID reservation = UUID.fromString("f2222222-2222-4222-8222-222222222222");
        UUID ticket = UUID.fromString("f3333333-3333-4333-8333-333333333333");
        Instant at = Instant.parse("2026-09-05T10:00:00Z");
        // Test spy at the handler boundary (no production failure switch).
        doThrow(new IllegalStateException("simulated transient projection failure"))
                .doCallRealMethod()
                .when(issuedHandler).project(any(), any());

        EventEnvelope<JsonNode> held = AnalyticsEnvelopeFactory.held(
                "p14-007-rollback-held", reservation, AnalyticsCanonicalFixtures.EVENT_E1, session,
                1, at);
        assertThat(driver.process(held)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);

        EventEnvelope<JsonNode> issued = AnalyticsEnvelopeFactory.issued(
                "p14-007-rollback-issued", ticket, reservation, session,
                AnalyticsCanonicalFixtures.EVENT_E1, at.plusSeconds(10));
        assertThatThrownBy(() -> driver.process(issued))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated transient");

        // Rolled back: no marker, no fact/aggregate mutation.
        assertThat(markerCount("p14-007-rollback-issued")).isZero();
        assertThat(ticketFacts.findById(ticket)).isEmpty();

        // Retry of the same eventId owns the event and projects exactly once.
        assertThat(driver.process(issued)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(markerCount("p14-007-rollback-issued")).isOne();
        assertThat(ticketFacts.findById(ticket)).isPresent();
        assertThat(driver.process(issued)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
    }

    // ------------------------------------------------------------------
    // 7.4 Commit-then-redelivery window (offset commit not durable)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.4 redelivery after DB commit sees the marker and mutates nothing")
    void redeliveryAfterCommitIsNoOp() {
        EventEnvelope<JsonNode> envelope = AnalyticsEnvelopeFactory.completed(
                "p14-007-redeliver-pay",
                UUID.fromString("b7777777-7777-4777-8777-777777777777"),
                UUID.fromString("a7777777-7777-4777-8777-777777777777"),
                UUID.fromString("17777777-7777-4777-8777-777777777777"),
                AnalyticsCanonicalFixtures.EVENT_E1,
                "200.00", "RON", Instant.parse("2026-09-05T10:05:00Z"));

        assertThat(processor.process(envelope, new ConsumerRecordMetadata(
                        "seatflow.payment.events", 0, 7L, "same-key")))
                .isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        String first = snapshot();

        // Kafka redelivers because the offset commit was not durable; the DB already committed.
        assertThat(processor.process(envelope, new ConsumerRecordMetadata(
                        "seatflow.payment.events", 0, 7L, "same-key")))
                .isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);
        assertThat(markerCount("p14-007-redeliver-pay")).isOne();
    }

    // ------------------------------------------------------------------
    // 7.12 Malformed known vs unknown event
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.12 unknown event type is ignored with no processed marker")
    void unknownEventTypeIsIgnored() {
        EventEnvelope<JsonNode> unknown = AnalyticsEnvelopeFactory.envelope(
                "p14-007-unknown-1", "UserRegistered", Instant.parse("2026-09-05T10:00:00Z"),
                AnalyticsEnvelopeFactory.mapper().createObjectNode().put("anything", "goes"));

        assertThat(driver.process(unknown)).isEqualTo(ProjectionEventProcessor.Outcome.IGNORED);
        assertThat(markerCount("p14-007-unknown-1")).isZero();

        // A subsequent valid event still processes normally.
        EventEnvelope<JsonNode> held = AnalyticsEnvelopeFactory.held(
                "p14-007-unknown-followup", AnalyticsCanonicalFixtures.R2,
                AnalyticsCanonicalFixtures.EVENT_E1, AnalyticsCanonicalFixtures.S2,
                1, AnalyticsCanonicalFixtures.D1_HOLD);
        assertThat(driver.process(held)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private int markerCount(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private String snapshot() {
        return AnalyticsSnapshot.snapshot(reservationFacts, paymentFacts, ticketFacts,
                revocationFacts, sessionFacts, sessionMetrics, sessionRevenue,
                dailyOperational, dailyRevenue);
    }
}
