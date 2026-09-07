package com.seatflow.analytics.integration;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-P14-007 sections 7.6-7.8 acceptance suite over real PostgreSQL.
 *
 * <p>Proves cross-topic reorder convergence, stale-event protection, and scan uniqueness.
 * Alternate orders only move records relative to other topic families; same-topic producer
 * order is preserved (eventIds carry per-order suffixes so every delivery is a distinct
 * semantic event, exactly as distinct producer records would be).
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AnalyticsProjectionTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalyticsOutOfOrderIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_007_ooo_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private ProjectionEventProcessor processor;

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
    private ProcessedEventRepository processedEvents;

    private AnalyticsSnapshot.Driver driver;

    @BeforeEach
    void cleanState() {
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
    // 7.6 Out-of-order convergence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.6 payment/confirmed/issue/scan reorder converges to one snapshot")
    void crossTopicReorderConverges() {
        UUID event = UUID.fromString("01111111-1111-4111-8111-111111111111");
        UUID session = UUID.fromString("02222222-2222-4222-8222-222222222222");
        UUID reservation = UUID.fromString("0a111111-1111-4111-8111-111111111111");
        UUID payment = UUID.fromString("0b111111-1111-4111-8111-111111111111");
        UUID ticket = UUID.fromString("0c111111-1111-4111-8111-111111111111");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        // Order A: reservation -> payment -> confirmed -> issued -> scanned.
        List<EventEnvelope<JsonNode>> orderA = List.of(
                AnalyticsEnvelopeFactory.held("ooo-a-held", reservation, event, session, 2, base),
                AnalyticsEnvelopeFactory.completed("ooo-a-pay", payment, reservation, session, event,
                        "200.00", "RON", base.plusSeconds(60)),
                AnalyticsEnvelopeFactory.confirmed("ooo-a-confirmed", reservation, event, session,
                        payment, base.plusSeconds(61)),
                AnalyticsEnvelopeFactory.issued("ooo-a-issued", ticket, reservation, session, event,
                        base.plusSeconds(120)),
                AnalyticsEnvelopeFactory.scanned("ooo-a-scanned", ticket, reservation, session,
                        base.plusSeconds(3600)));

        // Order B: payment + scan evidence first, reservation/confirm/issue later.
        // Same-topic producer order is preserved (held/confirmed are both reservation
        // events, issued/scanned are both ticket events); records move only relative
        // to other topic families, exactly as the task requires.
        List<EventEnvelope<JsonNode>> orderB = List.of(
                AnalyticsEnvelopeFactory.completed("ooo-b-pay", payment, reservation, session, event,
                        "200.00", "RON", base.plusSeconds(60)),
                AnalyticsEnvelopeFactory.held("ooo-b-held", reservation, event, session, 2, base),
                AnalyticsEnvelopeFactory.issued("ooo-b-issued", ticket, reservation, session, event,
                        base.plusSeconds(120)),
                AnalyticsEnvelopeFactory.confirmed("ooo-b-confirmed", reservation, event, session,
                        payment, base.plusSeconds(61)),
                AnalyticsEnvelopeFactory.scanned("ooo-b-scanned", ticket, reservation, session,
                        base.plusSeconds(3600)));

        orderA.forEach(driver::process);
        String first = snapshot();
        cleanState();
        orderB.forEach(driver::process);
        assertThat(snapshot()).isEqualTo(first);

        var metrics = sessionMetrics.findById(session).orElseThrow();
        assertThat(metrics.getReservationsCreated()).isOne();
        assertThat(metrics.getReservationsConfirmed()).isOne();
        assertThat(metrics.getPaymentsSucceeded()).isOne();
        assertThat(metrics.getTicketsIssued()).isOne();
        assertThat(metrics.getTicketsScanned()).isOne();
    }

    @Test
    @DisplayName("7.6 refund before/after reservation-refund evidence converges")
    void refundOrderConverges() {
        UUID event = UUID.fromString("03333333-3333-4333-8333-333333333333");
        UUID session = UUID.fromString("04444444-4444-4444-8444-444444444444");
        UUID reservation = UUID.fromString("0a444444-4444-4444-8444-444444444444");
        UUID payment = UUID.fromString("0b444444-4444-4444-8444-444444444444");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        List<EventEnvelope<JsonNode>> paymentFirst = List.of(
                AnalyticsEnvelopeFactory.held("ooo-r1-held", reservation, event, session, 1, base),
                AnalyticsEnvelopeFactory.completed("ooo-r1-pay", payment, reservation, session, event,
                        "200.00", "RON", base.plusSeconds(60)),
                AnalyticsEnvelopeFactory.paymentRefunded("ooo-r1-prefund", payment, reservation,
                        session, event, "200.00", "RON", base.plusSeconds(120)),
                AnalyticsEnvelopeFactory.reservationRefunded("ooo-r1-rrefund", reservation, event,
                        session, base.plusSeconds(121)));

        List<EventEnvelope<JsonNode>> reservationFirst = List.of(
                AnalyticsEnvelopeFactory.held("ooo-r2-held", reservation, event, session, 1, base),
                AnalyticsEnvelopeFactory.completed("ooo-r2-pay", payment, reservation, session, event,
                        "200.00", "RON", base.plusSeconds(60)),
                AnalyticsEnvelopeFactory.reservationRefunded("ooo-r2-rrefund", reservation, event,
                        session, base.plusSeconds(121)),
                AnalyticsEnvelopeFactory.paymentRefunded("ooo-r2-prefund", payment, reservation,
                        session, event, "200.00", "RON", base.plusSeconds(120)));

        paymentFirst.forEach(driver::process);
        String first = snapshot();
        cleanState();
        reservationFirst.forEach(driver::process);
        assertThat(snapshot()).isEqualTo(first);

        var revenue = sessionRevenue.findByEventSessionId(session);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().getRefundedRevenueMinor()).isEqualTo(20_000L);
        assertThat(revenue.getFirst().netRevenueMinor()).isZero();
    }

    @Test
    @DisplayName("7.6 scan before issue converges with issue before scan to one attendance")
    void scanBeforeIssueConverges() {
        UUID event = UUID.fromString("05555555-5555-4555-8555-555555555555");
        UUID session = UUID.fromString("06666666-6666-4666-8666-666666666666");
        UUID reservation = UUID.fromString("0a666666-6666-4666-8666-666666666666");
        UUID ticket = UUID.fromString("0c666666-6666-4666-8666-666666666666");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        List<EventEnvelope<JsonNode>> issueFirst = List.of(
                AnalyticsEnvelopeFactory.held("ooo-s1-held", reservation, event, session, 1, base),
                AnalyticsEnvelopeFactory.issued("ooo-s1-issued", ticket, reservation, session, event,
                        base.plusSeconds(10)),
                AnalyticsEnvelopeFactory.scanned("ooo-s1-scanned", ticket, reservation, session,
                        base.plusSeconds(20)));
        List<EventEnvelope<JsonNode>> scanFirst = List.of(
                AnalyticsEnvelopeFactory.scanned("ooo-s2-scanned", ticket, reservation, session,
                        base.plusSeconds(20)),
                AnalyticsEnvelopeFactory.held("ooo-s2-held", reservation, event, session, 1, base),
                AnalyticsEnvelopeFactory.issued("ooo-s2-issued", ticket, reservation, session, event,
                        base.plusSeconds(10)));

        issueFirst.forEach(driver::process);
        String first = snapshot();
        cleanState();
        scanFirst.forEach(driver::process);
        assertThat(snapshot()).isEqualTo(first);
        assertThat(sessionMetrics.findById(session).orElseThrow().getTicketsScanned()).isOne();
    }

    @Test
    @DisplayName("7.6/7.11 scan consumed before reservation correlation converges to max watermark")
    void scanBeforeReservationCorrelationConvergesToMaxWatermark() {
        List<EventEnvelope<JsonNode>> canonical = AnalyticsCanonicalFixtures.s3History();
        canonical.forEach(driver::process);
        String first = snapshot();
        assertThat(sessionFacts.findById(AnalyticsCanonicalFixtures.S3).orElseThrow()
                .getLastSourceEventAt()).isEqualTo(AnalyticsCanonicalFixtures.D2_S3_SCAN);

        cleanState();

        // Exact REV-005 broker race, deterministically: both ticket-topic records are
        // consumed before any reservation-topic correlation exists. The scan carries
        // session identity but no parent-event identity and the reservation is not yet
        // resolvable, so a conditional ensureSession skip would strand the watermark at
        // the older issue time (10:10) instead of the scan time (11:00). Same-topic
        // producer order is preserved (issued -> scanned, held -> confirmed); records
        // move only relative to other topic families.
        List<EventEnvelope<JsonNode>> ticketFirst = List.of(
                canonical.get(3), // issued 10:10
                canonical.get(4), // scanned 11:00
                canonical.get(0), // held 10:00
                canonical.get(2), // confirmed 10:06
                canonical.get(1)); // completed 10:05
        ticketFirst.forEach(driver::process);

        assertThat(snapshot()).isEqualTo(first);
        assertThat(sessionFacts.findById(AnalyticsCanonicalFixtures.S3).orElseThrow()
                .getLastSourceEventAt()).isEqualTo(AnalyticsCanonicalFixtures.D2_S3_SCAN);
    }

    @Test
    @DisplayName("7.6 reservation-scoped revocation before issue reconciles on later issue")
    void revocationBeforeIssueReconciles() {
        UUID event = UUID.fromString("07777777-7777-4777-8777-777777777777");
        UUID session = UUID.fromString("08888888-8888-4888-8888-888888888888");
        UUID reservation = UUID.fromString("0a888888-8888-4888-8888-888888888888");
        UUID ticket = UUID.fromString("0c888888-8888-4888-8888-888888888888");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        List<EventEnvelope<JsonNode>> revokeFirst = List.of(
                AnalyticsEnvelopeFactory.held("ooo-v1-held", reservation, event, session, 1, base),
                AnalyticsEnvelopeFactory.revokedForReservation("ooo-v1-revoked", reservation,
                        session, event, base.plusSeconds(10)),
                AnalyticsEnvelopeFactory.issued("ooo-v1-issued", ticket, reservation, session, event,
                        base.plusSeconds(20)));
        List<EventEnvelope<JsonNode>> issueFirst = List.of(
                AnalyticsEnvelopeFactory.held("ooo-v2-held", reservation, event, session, 1, base),
                AnalyticsEnvelopeFactory.issued("ooo-v2-issued", ticket, reservation, session, event,
                        base.plusSeconds(20)),
                AnalyticsEnvelopeFactory.revokedForTicket("ooo-v2-revoked", ticket, reservation,
                        session, event, base.plusSeconds(10)));

        revokeFirst.forEach(driver::process);
        String first = businessSnapshot();
        cleanState();
        issueFirst.forEach(driver::process);
        assertThat(businessSnapshot()).isEqualTo(first);

        // Revocation evidence is retained and reconciled: exactly one revocation either way.
        assertThat(sessionMetrics.findById(session).orElseThrow().getTicketsRevoked()).isOne();
        // The revoke-first path additionally retains the reservation-scoped evidence row
        // (triage evidence, not an outcome): document the difference explicitly instead of
        // hiding it inside snapshot equality.
        cleanState();
        revokeFirst.forEach(driver::process);
        assertThat(revocationFacts.findById(reservation)).isPresent();
    }

    @Test
    @DisplayName("7.6 refund observed before completion stays provisional until reconciliation")
    void refundBeforeCompletionIsProvisional() {
        UUID event = UUID.fromString("09999999-9999-4999-8999-999999999999");
        UUID session = UUID.fromString("0aaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID reservation = UUID.fromString("0a999999-9999-4999-8999-999999999999");
        UUID payment = UUID.fromString("0b999999-9999-4999-8999-999999999999");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        driver.process(AnalyticsEnvelopeFactory.held(
                "ooo-p-held", reservation, event, session, 2, base));
        driver.process(AnalyticsEnvelopeFactory.paymentRefunded(
                "ooo-p-refund-first", payment, reservation, session, event,
                "200.00", "RON", base.plusSeconds(30)));

        // Provisional: no financial aggregate, no negative net, no operational refund.
        assertThat(sessionRevenue.findAll()).isEmpty();
        assertThat(dailyRevenue.findAll()).isEmpty();
        assertThat(sessionMetrics.findById(session).orElseThrow().getRefundsCompleted()).isZero();

        driver.process(AnalyticsEnvelopeFactory.completed(
                "ooo-p-completed", payment, reservation, session, event,
                "200.00", "RON", base.plusSeconds(60)));

        var revenue = sessionRevenue.findByEventSessionId(session);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().getGrossRevenueMinor()).isEqualTo(20_000L);
        assertThat(revenue.getFirst().getRefundedRevenueMinor()).isEqualTo(20_000L);
        assertThat(revenue.getFirst().netRevenueMinor()).isZero();
        assertThat(sessionMetrics.findById(session).orElseThrow().getRefundsCompleted()).isOne();
    }

    // ------------------------------------------------------------------
    // 7.7 Older event cannot overwrite newer state
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.7 stale expiration after confirmation never reclassifies the reservation")
    void staleExpirationAfterConfirmationIgnored() {
        UUID event = AnalyticsCanonicalFixtures.EVENT_E1;
        UUID session = UUID.fromString("0bbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        UUID reservation = UUID.fromString("0abbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        UUID payment = UUID.fromString("0bbbbbb0-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        driver.process(AnalyticsEnvelopeFactory.held(
                "ooo-st-held", reservation, event, session, 1, base));
        driver.process(AnalyticsEnvelopeFactory.confirmed(
                "ooo-st-confirmed", reservation, event, session, payment, base.plusSeconds(60)));
        // Stale expiration carrying an older business time replays late.
        driver.process(AnalyticsEnvelopeFactory.expired(
                "ooo-st-expired", reservation, event, session, base.plusSeconds(30)));

        var metrics = sessionMetrics.findById(session).orElseThrow();
        assertThat(metrics.getReservationsConfirmed()).isOne();
        assertThat(metrics.getReservationsExpired()).isZero();
    }

    @Test
    @DisplayName("7.7 lifecycle metadata never fabricates session state, in any arrival order")
    void lifecycleEventsNeverFabricateSessionState() {
        UUID event = UUID.fromString("0ccccccc-cccc-4ccc-8ccc-cccccccccccc");
        Instant older = Instant.parse("2026-09-05T09:00:00Z");
        Instant newer = Instant.parse("2026-09-05T10:00:00Z");

        // Newer lifecycle receipt first, older replays late: neither may create session
        // facts nor capacity snapshots (no trusted capacity exists in current payloads).
        assertThat(driver.process(AnalyticsEnvelopeFactory.lifecycle(
                        "ooo-meta-newer", "EVENT_PUBLISHED", event, newer)))
                .isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        String first = snapshot();
        assertThat(driver.process(AnalyticsEnvelopeFactory.lifecycle(
                        "ooo-meta-older", "EVENT_CREATED", event, older)))
                .isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(snapshot()).isEqualTo(first);
        assertThat(sessionFacts.findAll()).isEmpty();
        assertThat(sessionMetrics.findAll()).isEmpty();
    }

    @Test
    @DisplayName("7.7 stale earlier confirmed after refund keeps the refunded outcome")
    void staleConfirmedAfterRefundKeepsOutcome() {
        UUID event = AnalyticsCanonicalFixtures.EVENT_E1;
        UUID session = UUID.fromString("0ddddddd-dddd-4ddd-8ddd-dddddddddddd");
        UUID reservation = UUID.fromString("0adddddd-dddd-4ddd-8ddd-dddddddddddd");
        UUID payment = UUID.fromString("0bdddddd-dddd-4ddd-8ddd-dddddddddddd");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        driver.process(AnalyticsEnvelopeFactory.held(
                "ooo-cr-held", reservation, event, session, 1, base));
        driver.process(AnalyticsEnvelopeFactory.completed(
                "ooo-cr-pay", payment, reservation, session, event,
                "200.00", "RON", base.plusSeconds(60)));
        driver.process(AnalyticsEnvelopeFactory.confirmed(
                "ooo-cr-confirmed", reservation, event, session, payment, base.plusSeconds(61)));
        driver.process(AnalyticsEnvelopeFactory.paymentRefunded(
                "ooo-cr-prefund", payment, reservation, session, event,
                "200.00", "RON", base.plusSeconds(120)));
        driver.process(AnalyticsEnvelopeFactory.reservationRefunded(
                "ooo-cr-rrefund", reservation, event, session, base.plusSeconds(121)));
        // Stale earlier confirmation replays late (e.g. retained-partition replay).
        driver.process(AnalyticsEnvelopeFactory.confirmed(
                "ooo-cr-stale-confirmed", reservation, event, session, payment,
                base.plusSeconds(30)));

        var metrics = sessionMetrics.findById(session).orElseThrow();
        assertThat(metrics.getReservationsConfirmed()).isOne();
        assertThat(metrics.getRefundsCompleted()).isOne();
        assertThat(metrics.getReservationsExpired()).isZero();
        var revenue = sessionRevenue.findByEventSessionId(session);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().netRevenueMinor()).isZero();
    }

    // ------------------------------------------------------------------
    // 7.8 Scan uniqueness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.8 repeated scans of one ticket yield exactly one attendance unit")
    void repeatedScansYieldOneAttendance() {
        List<EventEnvelope<JsonNode>> history = AnalyticsCanonicalFixtures.s1History();
        // S1 T1 accepted scan, then two more distinct scan attempts for the same ticket.
        driver.process(history.get(0));
        driver.process(history.get(3));
        driver.process(history.get(5));
        driver.process(AnalyticsEnvelopeFactory.scanned(
                "p14-007-scan-dup-1", AnalyticsCanonicalFixtures.T1,
                AnalyticsCanonicalFixtures.R1, AnalyticsCanonicalFixtures.S1,
                AnalyticsCanonicalFixtures.D1_SCAN.plusSeconds(5)));
        driver.process(AnalyticsEnvelopeFactory.scannedAs(
                "p14-007-scan-validated-1", "TicketValidated", AnalyticsCanonicalFixtures.T1,
                AnalyticsCanonicalFixtures.R1, AnalyticsCanonicalFixtures.S1,
                AnalyticsCanonicalFixtures.D1_SCAN.plusSeconds(10)));

        var metrics = sessionMetrics.findById(AnalyticsCanonicalFixtures.S1).orElseThrow();
        assertThat(metrics.getTicketsIssued()).isOne();
        assertThat(metrics.getTicketsScanned()).isOne();
    }

    @Test
    @DisplayName("7.8 accepted scan then revocation keeps one attendance unit, not zero-or-two")
    void scanThenRevokeKeepsOneAttendance() {
        driver.process(AnalyticsEnvelopeFactory.held(
                "ooo-sr-held", AnalyticsCanonicalFixtures.R3,
                AnalyticsCanonicalFixtures.EVENT_E2, AnalyticsCanonicalFixtures.S3,
                1, AnalyticsCanonicalFixtures.D2_S3_HOLD));
        driver.process(AnalyticsEnvelopeFactory.issued(
                "ooo-sr-issued", AnalyticsCanonicalFixtures.T3, AnalyticsCanonicalFixtures.R3,
                AnalyticsCanonicalFixtures.S3, AnalyticsCanonicalFixtures.EVENT_E2,
                AnalyticsCanonicalFixtures.D2_S3_ISSUE));
        driver.process(AnalyticsEnvelopeFactory.scanned(
                "ooo-sr-scanned", AnalyticsCanonicalFixtures.T3, AnalyticsCanonicalFixtures.R3,
                AnalyticsCanonicalFixtures.S3, AnalyticsCanonicalFixtures.D2_S3_SCAN));
        driver.process(AnalyticsEnvelopeFactory.revokedForTicket(
                "ooo-sr-revoked", AnalyticsCanonicalFixtures.T3, AnalyticsCanonicalFixtures.R3,
                AnalyticsCanonicalFixtures.S3, AnalyticsCanonicalFixtures.EVENT_E2,
                AnalyticsCanonicalFixtures.D2_S3_SCAN.plusSeconds(60)));

        var metrics = sessionMetrics.findById(AnalyticsCanonicalFixtures.S3).orElseThrow();
        assertThat(metrics.getTicketsScanned()).isOne();
        assertThat(metrics.getTicketsRevoked()).isOne();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String snapshot() {
        return AnalyticsSnapshot.snapshot(reservationFacts, paymentFacts, ticketFacts,
                revocationFacts, sessionFacts, sessionMetrics, sessionRevenue,
                dailyOperational, dailyRevenue);
    }

    /**
     * Outcome-level snapshot: fact/aggregate business state excluding the reservation-scoped
     * revocation evidence table ({@code BREV|}), whose presence legitimately depends on
     * whether revocation evidence arrived before ticket correlation.
     */
    private String businessSnapshot() {
        return snapshot().lines()
                .filter(line -> !line.startsWith("BREV|"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
