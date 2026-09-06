package com.seatflow.analytics.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatflow.analytics.messaging.AnalyticsEventDispatcher;
import com.seatflow.analytics.messaging.AnalyticsEventValidationException;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionEventProcessor;
import com.seatflow.analytics.messaging.handlers.EventLifecycleProjectionHandler;
import com.seatflow.analytics.messaging.handlers.PaymentCompletedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.PaymentFailedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.PaymentRefundedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationCancelledProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationConfirmedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationExpiredProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationHeldProjectionHandler;
import com.seatflow.analytics.messaging.handlers.ReservationRefundedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.TicketIssuedProjectionHandler;
import com.seatflow.analytics.messaging.handlers.TicketRevocationProjectionHandler;
import com.seatflow.analytics.messaging.handlers.TicketScanProjectionHandler;
import com.seatflow.analytics.repository.AnalyticsPaymentFactRepository;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository;
import com.seatflow.analytics.repository.AnalyticsReservationFactRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketRevocationFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.DailyRevenueMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.repository.ProcessedEventRepository;
import com.seatflow.common.events.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TASK-P14-003 acceptance suite: deterministic facts-first projections over real PostgreSQL.
 *
 * <p>Every test drives the full P14-002 claim path
 * ({@link ProjectionEventProcessor} + real adapters + {@link AnalyticsProjectionReconciler}),
 * so replay, ordering, and idempotency behavior is verified where it actually runs. Snapshots
 * exclude audit timestamps ({@code updated_at}/{@code processed_at}) but include every business
 * fact and aggregate column.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProjectionDeterminismIntegrationTest.TestConfig.class)
class ProjectionDeterminismIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_003_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        ReservationProjectionHandler reservationProjectionHandler(
                AnalyticsReservationFactRepository reservations,
                AnalyticsPaymentFactRepository payments,
                AnalyticsTicketFactRepository tickets,
                AnalyticsSessionFactRepository sessions,
                EventSessionProjectionHandler sessionHandler) {
            return new ReservationProjectionHandler(reservations, payments, tickets, sessions, sessionHandler);
        }

        @Bean
        PaymentProjectionHandler paymentProjectionHandler(
                AnalyticsPaymentFactRepository payments,
                AnalyticsReservationFactRepository reservations,
                EventSessionProjectionHandler sessionHandler) {
            return new PaymentProjectionHandler(payments, reservations, sessionHandler);
        }

        @Bean
        TicketProjectionHandler ticketProjectionHandler(
                AnalyticsTicketFactRepository tickets,
                AnalyticsTicketRevocationFactRepository revocations,
                AnalyticsReservationFactRepository reservations,
                EventSessionProjectionHandler sessionHandler) {
            return new TicketProjectionHandler(tickets, revocations, reservations, sessionHandler);
        }

        @Bean
        EventSessionProjectionHandler eventSessionProjectionHandler(
                AnalyticsSessionFactRepository sessions) {
            return new EventSessionProjectionHandler(sessions);
        }

        @Bean
        AnalyticsProjectionQueryRepository projectionQueryRepository() {
            return new AnalyticsProjectionQueryRepository();
        }

        @Bean
        AnalyticsProjectionReconciler reconciler(
                AnalyticsProjectionQueryRepository queries,
                AnalyticsSessionFactRepository sessions,
                EventSessionMetricRepository sessionMetrics,
                EventSessionRevenueMetricRepository sessionRevenue,
                DailyOperationalMetricRepository dailyOperational,
                DailyRevenueMetricRepository dailyRevenue) {
            return new AnalyticsProjectionReconciler(queries, sessions, sessionMetrics,
                    sessionRevenue, dailyOperational, dailyRevenue);
        }

        @Bean
        ReservationHeldProjectionHandler heldAdapter(
                ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
            return new ReservationHeldProjectionHandler(reservations, reconciler);
        }

        @Bean
        ReservationConfirmedProjectionHandler confirmedAdapter(
                ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
            return new ReservationConfirmedProjectionHandler(reservations, reconciler);
        }

        @Bean
        ReservationExpiredProjectionHandler expiredAdapter(
                ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
            return new ReservationExpiredProjectionHandler(reservations, reconciler);
        }

        @Bean
        ReservationCancelledProjectionHandler cancelledAdapter(
                ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
            return new ReservationCancelledProjectionHandler(reservations, reconciler);
        }

        @Bean
        ReservationRefundedProjectionHandler reservationRefundedAdapter(
                ReservationProjectionHandler reservations, AnalyticsProjectionReconciler reconciler) {
            return new ReservationRefundedProjectionHandler(reservations, reconciler);
        }

        @Bean
        PaymentCompletedProjectionHandler completedAdapter(
                PaymentProjectionHandler payments, AnalyticsProjectionReconciler reconciler) {
            return new PaymentCompletedProjectionHandler(payments, reconciler);
        }

        @Bean
        PaymentFailedProjectionHandler failedAdapter(
                PaymentProjectionHandler payments, AnalyticsProjectionReconciler reconciler) {
            return new PaymentFailedProjectionHandler(payments, reconciler);
        }

        @Bean
        PaymentRefundedProjectionHandler paymentRefundedAdapter(
                PaymentProjectionHandler payments, AnalyticsProjectionReconciler reconciler) {
            return new PaymentRefundedProjectionHandler(payments, reconciler);
        }

        @Bean
        TicketIssuedProjectionHandler issuedAdapter(
                TicketProjectionHandler tickets, AnalyticsProjectionReconciler reconciler) {
            return new TicketIssuedProjectionHandler(tickets, reconciler);
        }

        @Bean
        TicketRevocationProjectionHandler revocationAdapter(
                TicketProjectionHandler tickets, AnalyticsProjectionReconciler reconciler) {
            return new TicketRevocationProjectionHandler(tickets, reconciler);
        }

        @Bean
        TicketScanProjectionHandler scanAdapter(
                TicketProjectionHandler tickets, AnalyticsProjectionReconciler reconciler) {
            return new TicketScanProjectionHandler(tickets, reconciler);
        }

        @Bean
        EventLifecycleProjectionHandler lifecycleAdapter(
                EventSessionProjectionHandler sessions, AnalyticsProjectionReconciler reconciler) {
            return new EventLifecycleProjectionHandler(sessions, reconciler);
        }

        @Bean
        AnalyticsEventDispatcher dispatcher(
                ReservationHeldProjectionHandler held,
                ReservationConfirmedProjectionHandler confirmed,
                ReservationExpiredProjectionHandler expired,
                ReservationCancelledProjectionHandler cancelled,
                ReservationRefundedProjectionHandler reservationRefunded,
                PaymentCompletedProjectionHandler completed,
                PaymentFailedProjectionHandler failed,
                PaymentRefundedProjectionHandler paymentRefunded,
                TicketIssuedProjectionHandler issued,
                TicketRevocationProjectionHandler revocation,
                TicketScanProjectionHandler scan,
                EventLifecycleProjectionHandler lifecycle) {
            return new AnalyticsEventDispatcher(List.of(held, confirmed, expired, cancelled,
                    reservationRefunded, completed, failed, paymentRefunded, issued, revocation,
                    scan, lifecycle));
        }

        @Bean
        ProjectionEventProcessor processor(
                ProcessedEventRepository repository, AnalyticsEventDispatcher dispatcher) {
            return new ProjectionEventProcessor(repository, dispatcher);
        }
    }

    @Autowired
    private ProjectionEventProcessor processor;

    @Autowired
    private AnalyticsProjectionReconciler reconciler;

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

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicLong offsets = new AtomicLong();

    @BeforeEach
    void cleanState() {
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
    // Replay determinism + rebuild
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Same eventIds redelivered are no-ops with an identical snapshot")
    void replayIsDeterministic() {
        List<EventEnvelope<JsonNode>> history = canonicalHistory();
        history.forEach(this::process);
        String first = snapshot();

        List<ProjectionEventProcessor.Outcome> outcomes =
                history.stream().map(this::process).toList();
        assertThat(outcomes).allMatch(o -> o == ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);
    }

    @Test
    @DisplayName("Rebuild from retained events on an empty DB converges to the same snapshot")
    void rebuildFromEmptyDbConverges() {
        List<EventEnvelope<JsonNode>> history = canonicalHistory();
        history.forEach(this::process);
        String first = snapshot();

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

        history.forEach(this::process);
        assertThat(snapshot()).isEqualTo(first);
    }

    // ------------------------------------------------------------------
    // Cross-topic ordering convergence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Payment before reservation converges with reservation before payment")
    void paymentBeforeReservationConverges() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-01T10:00:00Z");

        List<EventEnvelope<JsonNode>> reservationFirst = List.of(
                held("e1", reservationId, eventId, sessionId, 2, base),
                completed("e2", paymentId, reservationId, sessionId, eventId, "150.00", "RON",
                        base.plusSeconds(60)));
        List<EventEnvelope<JsonNode>> paymentFirst = List.of(
                completed("e2", paymentId, reservationId, sessionId, eventId, "150.00", "RON",
                        base.plusSeconds(60)),
                held("e1", reservationId, eventId, sessionId, 2, base));

        reservationFirst.forEach(this::process);
        String first = snapshot();
        cleanState();
        paymentFirst.forEach(this::process);
        assertThat(snapshot()).isEqualTo(first);

        // Converged state: one completed payment with session revenue in RON.
        assertThat(sessionRevenue.findAll()).hasSize(1);
        assertThat(sessionRevenue.findAll().getFirst().getGrossRevenueMinor()).isEqualTo(15000L);
        assertThat(sessionMetrics.findById(sessionId)).isPresent();
        assertThat(sessionMetrics.findById(sessionId).get().getPaymentsSucceeded()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Scan before issue converges with issue before scan to one attendance")
    void scanBeforeIssueConverges() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-02T10:00:00Z");

        List<EventEnvelope<JsonNode>> issueFirst = List.of(
                held("e1", reservationId, eventId, sessionId, 1, base),
                issued("e2", ticketId, reservationId, sessionId, eventId, base.plusSeconds(10)),
                scanned("e3", ticketId, reservationId, sessionId, base.plusSeconds(20)));
        List<EventEnvelope<JsonNode>> scanFirst = List.of(
                scanned("e3", ticketId, reservationId, sessionId, base.plusSeconds(20)),
                held("e1", reservationId, eventId, sessionId, 1, base),
                issued("e2", ticketId, reservationId, sessionId, eventId, base.plusSeconds(10)));

        issueFirst.forEach(this::process);
        String first = snapshot();
        cleanState();
        scanFirst.forEach(this::process);
        assertThat(snapshot()).isEqualTo(first);
        assertThat(sessionMetrics.findById(sessionId).get().getTicketsScanned()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Refund before completion stays provisional until completion validates it")
    void refundFirstIsProvisionalUntilCompletion() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-03T10:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 2, base));
        process(paymentRefunded("e2", paymentId, reservationId, sessionId, eventId,
                "150.00", "RON", base.plusSeconds(30)));

        // Provisional: no financial aggregate, no negative net, no operational refund.
        assertThat(sessionRevenue.findAll()).isEmpty();
        assertThat(dailyRevenue.findAll()).isEmpty();
        assertThat(sessionMetrics.findById(sessionId).get().getRefundsCompleted()).isZero();

        process(completed("e3", paymentId, reservationId, sessionId, eventId, "150.00", "RON",
                base.plusSeconds(60)));

        assertThat(sessionRevenue.findAll()).hasSize(1);
        var revenue = sessionRevenue.findAll().getFirst();
        assertThat(revenue.getGrossRevenueMinor()).isEqualTo(15000L);
        assertThat(revenue.getRefundedRevenueMinor()).isEqualTo(15000L);
        assertThat(revenue.netRevenueMinor()).isZero();
        assertThat(sessionMetrics.findById(sessionId).get().getRefundsCompleted()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Reservation lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Held then confirmed counts one created and one confirmed")
    void heldThenConfirmed() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-04T10:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 3, base));
        process(confirmed("e2", reservationId, eventId, sessionId, base.plusSeconds(60)));

        var metrics = sessionMetrics.findById(sessionId).get();
        assertThat(metrics.getReservationsCreated()).isEqualTo(1L);
        assertThat(metrics.getReservationsConfirmed()).isEqualTo(1L);
        assertThat(metrics.getReservationsExpired()).isZero();
    }

    @Test
    @DisplayName("Held then expired counts one created and one expired")
    void heldThenExpired() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-04T11:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(expired("e2", reservationId, eventId, sessionId, base.plusSeconds(900)));

        var metrics = sessionMetrics.findById(sessionId).get();
        assertThat(metrics.getReservationsCreated()).isEqualTo(1L);
        assertThat(metrics.getReservationsConfirmed()).isZero();
        assertThat(metrics.getReservationsExpired()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Stale expiration after confirmation never reclassifies the reservation")
    void staleExpirationAfterConfirmationIgnored() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-04T12:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(confirmed("e2", reservationId, eventId, sessionId, base.plusSeconds(60)));
        // Stale expiration carrying an older business time replays late.
        process(expired("e3", reservationId, eventId, sessionId, base.plusSeconds(30)));

        var metrics = sessionMetrics.findById(sessionId).get();
        assertThat(metrics.getReservationsConfirmed()).isEqualTo(1L);
        assertThat(metrics.getReservationsExpired()).isZero();
    }

    @Test
    @DisplayName("Refunded reservation stays historically confirmed with a separate refund")
    void refundedReservationStaysConfirmed() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-04T13:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 2, base));
        process(completed("e2", paymentId, reservationId, sessionId, eventId, "200.00", "RON",
                base.plusSeconds(60)));
        process(confirmed("e3", reservationId, eventId, sessionId, base.plusSeconds(61)));
        process(paymentRefunded("e4", paymentId, reservationId, sessionId, eventId,
                "200.00", "RON", base.plusSeconds(120)));
        process(reservationRefunded("e5", reservationId, eventId, sessionId, base.plusSeconds(121)));

        var metrics = sessionMetrics.findById(sessionId).get();
        assertThat(metrics.getReservationsConfirmed()).isEqualTo(1L);
        assertThat(metrics.getReservationsExpired()).isZero();
        assertThat(metrics.getRefundsCompleted()).isEqualTo(1L);
        var revenue = sessionRevenue.findAll().getFirst();
        assertThat(revenue.getGrossRevenueMinor()).isEqualTo(20000L);
        assertThat(revenue.getRefundedRevenueMinor()).isEqualTo(20000L);
        assertThat(revenue.netRevenueMinor()).isZero();
    }

    @Test
    @DisplayName("Cancelled hold counts created only, never confirmed or expired")
    void cancelledHoldCountsCreatedOnly() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-04T14:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(cancelled("e2", reservationId, eventId, sessionId, base.plusSeconds(60)));

        var metrics = sessionMetrics.findById(sessionId).get();
        assertThat(metrics.getReservationsCreated()).isEqualTo(1L);
        assertThat(metrics.getReservationsConfirmed()).isZero();
        assertThat(metrics.getReservationsExpired()).isZero();
    }

    // ------------------------------------------------------------------
    // Aggregate grain / multi-currency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RON plus EUR activity yields one operational row and two revenue rows")
    void multiCurrencyGrain() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        UUID resRon = UUID.randomUUID();
        UUID payRon = UUID.randomUUID();
        UUID resEur = UUID.randomUUID();
        UUID payEur = UUID.randomUUID();
        process(held("e1", resRon, eventId, sessionId, 2, base));
        process(completed("e2", payRon, resRon, sessionId, eventId, "150.00", "RON", base.plusSeconds(10)));
        process(held("e3", resEur, eventId, sessionId, 1, base.plusSeconds(20)));
        process(completed("e4", payEur, resEur, sessionId, eventId, "75.50", "EUR", base.plusSeconds(30)));

        assertThat(sessionMetrics.findAll()).hasSize(1);
        var operational = sessionMetrics.findById(sessionId).get();
        assertThat(operational.getReservationsCreated()).isEqualTo(2L);
        assertThat(operational.getPaymentsSucceeded()).isEqualTo(2L);

        var revenues = sessionRevenue.findByEventSessionId(sessionId);
        assertThat(revenues).hasSize(2);
        var byCurrency = revenues.stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.seatflow.analytics.model.entity.EventSessionRevenueMetric::getCurrency,
                        r -> r));
        assertThat(byCurrency.get("RON").getGrossRevenueMinor()).isEqualTo(15000L);
        assertThat(byCurrency.get("EUR").getGrossRevenueMinor()).isEqualTo(7550L);

        // Operational counts summed across sessions must not multiply by currency rows.
        long totalCreated = sessionMetrics.findAll().stream()
                .mapToLong(m -> m.getReservationsCreated()).sum();
        assertThat(totalCreated).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // Financial correctness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Failure evidence adds zero gross while success still counts")
    void failureAddsZeroGross() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-06T10:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(failed("e2", paymentId, reservationId, sessionId, eventId, base.plusSeconds(10)));
        assertThat(sessionRevenue.findAll()).isEmpty();
        assertThat(sessionMetrics.findById(sessionId).get().getPaymentsWithFailure()).isEqualTo(1L);

        process(completed("e3", paymentId, reservationId, sessionId, eventId, "99.99", "RON",
                base.plusSeconds(20)));
        var metrics = sessionMetrics.findById(sessionId).get();
        assertThat(metrics.getPaymentsWithFailure()).isEqualTo(1L);
        assertThat(metrics.getPaymentsSucceeded()).isEqualTo(1L);
        assertThat(sessionRevenue.findAll().getFirst().getGrossRevenueMinor()).isEqualTo(9999L);
    }

    @Test
    @DisplayName("Refund larger than completion is rejected and stays out of aggregates")
    void oversizedRefundIsRejected() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-06T11:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(completed("e2", paymentId, reservationId, sessionId, eventId, "100.00", "RON",
                base.plusSeconds(10)));

        var badRefund = paymentRefunded("e3", paymentId, reservationId, sessionId, eventId,
                "100.01", "RON", base.plusSeconds(20));
        assertThatThrownBy(() -> process(badRefund))
                .isInstanceOf(AnalyticsEventValidationException.class);

        var revenue = sessionRevenue.findAll().getFirst();
        assertThat(revenue.getGrossRevenueMinor()).isEqualTo(10000L);
        assertThat(revenue.getRefundedRevenueMinor()).isZero();
        assertThat(revenue.netRevenueMinor()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Currency change across completion and refund is a contract violation")
    void currencyChangeIsRejected() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-06T12:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(completed("e2", paymentId, reservationId, sessionId, eventId, "100.00", "RON",
                base.plusSeconds(10)));

        var crossCurrency = paymentRefunded("e3", paymentId, reservationId, sessionId, eventId,
                "100.00", "EUR", base.plusSeconds(20));
        assertThatThrownBy(() -> process(crossCurrency))
                .isInstanceOf(AnalyticsEventValidationException.class);
        assertThat(sessionRevenue.findByEventSessionId(sessionId)).hasSize(1);
    }

    @Test
    @DisplayName("Completion and refund on different UTC dates touch both daily rows")
    void grossAndRefundOnDifferentDates() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        process(held("e1", reservationId, eventId, sessionId, 1,
                Instant.parse("2026-09-07T23:50:00Z")));
        process(completed("e2", paymentId, reservationId, sessionId, eventId, "50.00", "RON",
                Instant.parse("2026-09-07T23:55:00Z")));
        process(paymentRefunded("e3", paymentId, reservationId, sessionId, eventId, "50.00", "RON",
                Instant.parse("2026-09-08T00:10:00Z")));

        assertThat(dailyRevenue.findAll()).hasSize(2);
        var dayOne = dailyRevenue.findAll().stream()
                .filter(r -> r.getMetricDate().toString().equals("2026-09-07")).findFirst().get();
        var dayTwo = dailyRevenue.findAll().stream()
                .filter(r -> r.getMetricDate().toString().equals("2026-09-08")).findFirst().get();
        assertThat(dayOne.getGrossRevenueMinor()).isEqualTo(5000L);
        assertThat(dayOne.getRefundedRevenueMinor()).isZero();
        assertThat(dayTwo.getGrossRevenueMinor()).isZero();
        assertThat(dayTwo.getRefundedRevenueMinor()).isEqualTo(5000L);
    }

    // ------------------------------------------------------------------
    // Ticket / attendance
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Repeated scans of one ticket yield exactly one attendance unit")
    void repeatedScansYieldOneAttendance() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-09T10:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(issued("e2", ticketId, reservationId, sessionId, eventId, base.plusSeconds(10)));
        process(scanned("e3", ticketId, reservationId, sessionId, base.plusSeconds(20)));
        process(scanned("e4", ticketId, reservationId, sessionId, base.plusSeconds(25)));
        process(scannedWithType("e5", "TicketValidated", ticketId, reservationId, sessionId,
                base.plusSeconds(30)));

        var metrics = sessionMetrics.findById(sessionId).get();
        assertThat(metrics.getTicketsIssued()).isEqualTo(1L);
        assertThat(metrics.getTicketsScanned()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Reservation-scoped revocation is retained and reconciled on later issue")
    void reservationScopedRevocationReconciles() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-09T11:00:00Z");

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(batchRevoked("e2", reservationId, sessionId, eventId, base.plusSeconds(10)));
        // No ticket identity known yet: nothing revoked, but evidence retained.
        assertThat(sessionMetrics.findById(sessionId).get().getTicketsRevoked()).isZero();
        assertThat(revocationFacts.findById(reservationId)).isPresent();

        process(issued("e3", ticketId, reservationId, sessionId, eventId, base.plusSeconds(20)));
        var metrics = sessionMetrics.findById(sessionId).get();
        assertThat(metrics.getTicketsIssued()).isEqualTo(1L);
        assertThat(metrics.getTicketsRevoked()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Correction / reconciliation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Session correction moves money without stale contributions")
    void sessionCorrectionMovesMoney() {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-10T10:00:00Z");

        process(held("e1", reservationId, eventId, sessionA, 1, base));
        process(completed("e2", paymentId, reservationId, sessionA, eventId, "80.00", "RON",
                base.plusSeconds(10)));
        // Valid correction: same payment identity recorrelated to session B.
        process(completed("e3", paymentId, reservationId, sessionB, eventId, "80.00", "RON",
                base.plusSeconds(20)));

        assertThat(sessionRevenue.findByEventSessionId(sessionB)).hasSize(1);
        assertThat(sessionRevenue.findByEventSessionId(sessionB).getFirst().getGrossRevenueMinor())
                .isEqualTo(8000L);
        // Session A must be recomputed too; because the reservation fact still points at A,
        // the payment correction only moves payment-derived buckets out of A.
        var metricsA = sessionMetrics.findById(sessionA).get();
        assertThat(metricsA.getPaymentsSucceeded()).isZero();
        assertThat(metricsA.getReservationsCreated()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // Rates
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Conversion uses the created cohort so spanning purchases cannot exceed 100 percent")
    void conversionUsesCreatedCohort() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant dayOne = Instant.parse("2026-09-11T10:00:00Z");
        Instant dayTwo = Instant.parse("2026-09-12T10:00:00Z");

        UUID resOne = UUID.randomUUID();
        UUID resTwo = UUID.randomUUID();
        process(held("e1", resOne, eventId, sessionId, 1, dayOne));
        process(held("e2", resTwo, eventId, sessionId, 1, dayOne));
        // Only the first reservation purchases, one day later.
        UUID payOne = UUID.randomUUID();
        process(completed("e3", payOne, resOne, sessionId, eventId, "60.00", "RON", dayTwo));
        process(confirmed("e4", resOne, eventId, sessionId, dayTwo));

        var cohort = reconciler.conversionCohort(dayOne, dayTwo, eventId, sessionId);
        assertThat(cohort.denominator()).isEqualTo(2L);
        assertThat(cohort.numerator()).isEqualTo(1L);
        assertThat(AnalyticsProjectionReconciler.conversionRate(cohort)).hasValue(0.5);

        var empty = reconciler.conversionCohort(
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
                eventId, sessionId);
        assertThat(AnalyticsProjectionReconciler.conversionRate(empty)).isEmpty();
    }

    @Test
    @DisplayName("Occupancy is unavailable without a trusted capacity snapshot")
    void occupancyUnavailableWithoutCapacity() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-13T10:00:00Z");
        UUID reservationId = UUID.randomUUID();

        process(held("e1", reservationId, eventId, sessionId, 1, base));
        process(issued("e2", UUID.randomUUID(), reservationId, sessionId, eventId,
                base.plusSeconds(10)));

        var occupancy = reconciler.occupancy(sessionId);
        assertThat(occupancy.capacitySnapshot()).isNull();
        assertThat(AnalyticsProjectionReconciler.occupancyRate(occupancy)).isEmpty();

        var attendance = reconciler.attendance(sessionId);
        assertThat(attendance.eligibleIssued()).isEqualTo(1L);
        assertThat(AnalyticsProjectionReconciler.attendanceRate(attendance)).hasValue(0.0);
    }

    @Test
    @DisplayName("Malformed optional correlation fails non-retryably, never as transient")
    void malformedOptionalCorrelationIsValidationError() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("reservationId", UUID.randomUUID().toString());
        payload.put("eventSessionId", "not-a-uuid");
        payload.put("amount", "10.00");
        payload.put("currency", "RON");
        payload.put("occurredAt", Instant.parse("2026-09-14T10:00:00Z").toString());
        var badEnvelope = envelope("bad-uuid-1", "PaymentCompleted",
                Instant.parse("2026-09-14T10:00:00Z"), payload);

        assertThatThrownBy(() -> process(badEnvelope))
                .isInstanceOf(AnalyticsEventValidationException.class);
        assertThat(paymentFacts.count()).isZero();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ProjectionEventProcessor.Outcome process(EventEnvelope<JsonNode> envelope) {
        String topic = switch (envelope.eventType()) {
            case String s when s.startsWith("Reservation") -> "seatflow.reservation.events";
            case String s when s.startsWith("Payment") -> "seatflow.payment.events";
            case String s when s.startsWith("Ticket") -> "seatflow.ticket.events";
            default -> "seatflow.event.events";
        };
        return processor.process(envelope,
                new ConsumerRecordMetadata(topic, 0, offsets.getAndIncrement(), "key"));
    }

    private String snapshot() {
        List<String> lines = new ArrayList<>();
        reservationFacts.findAll().stream()
                .sorted(Comparator.comparing(r -> r.getReservationId().toString()))
                .forEach(r -> lines.add("RES|" + r.getReservationId() + "|" + r.getEventId() + "|"
                        + r.getEventSessionId() + "|" + r.getCreatedAt() + "|" + r.getConfirmedAt()
                        + "|" + r.getExpiredAt() + "|" + r.getRefundedAt() + "|" + r.getCancelledAt()
                        + "|" + r.getSeatCount() + "|" + r.getCurrency() + "|" + r.getQuotedTotalMinor()
                        + "|" + r.getLastSourceEventAt()));
        paymentFacts.findAll().stream()
                .sorted(Comparator.comparing(p -> p.getPaymentId().toString()))
                .forEach(p -> lines.add("PAY|" + p.getPaymentId() + "|" + p.getReservationId() + "|"
                        + p.getEventSessionId() + "|" + p.getLatestStatus() + "|" + p.getCurrency()
                        + "|" + p.getCompletedAmountMinor() + "|" + p.getRefundedAmountMinor() + "|"
                        + p.getCompletedAt() + "|" + p.getFailedAt() + "|" + p.getRefundedAt()
                        + "|" + p.getLastSourceEventAt()));
        ticketFacts.findAll().stream()
                .sorted(Comparator.comparing(t -> t.getTicketId().toString()))
                .forEach(t -> lines.add("TIX|" + t.getTicketId() + "|" + t.getReservationId() + "|"
                        + t.getEventSessionId() + "|" + t.getIssuedAt() + "|" + t.getRevokedAt()
                        + "|" + t.getFirstScannedAt() + "|" + t.getStatus()
                        + "|" + t.getLastSourceEventAt()));
        revocationFacts.findAll().stream()
                .sorted(Comparator.comparing(r -> r.getReservationId().toString()))
                .forEach(r -> lines.add("BREV|" + r.getReservationId() + "|" + r.getEventSessionId()
                        + "|" + r.getRevokedAt() + "|" + r.getLastSourceEventAt()));
        sessionFacts.findAll().stream()
                .sorted(Comparator.comparing(s -> s.getEventSessionId().toString()))
                .forEach(s -> lines.add("SES|" + s.getEventSessionId() + "|" + s.getEventId() + "|"
                        + s.getCapacitySnapshot() + "|" + s.getLastSourceEventAt()));
        sessionMetrics.findAll().stream()
                .sorted(Comparator.comparing(m -> m.getEventSessionId().toString()))
                .forEach(m -> lines.add("SM|" + m.getEventSessionId() + "|" + m.getEventId() + "|"
                        + m.getCapacitySnapshot() + "|" + m.getReservationsCreated() + "|"
                        + m.getReservationsConfirmed() + "|" + m.getReservationsExpired() + "|"
                        + m.getPaymentsSucceeded() + "|" + m.getPaymentsWithFailure() + "|"
                        + m.getRefundsCompleted() + "|" + m.getTicketsIssued() + "|"
                        + m.getTicketsRevoked() + "|" + m.getTicketsScanned() + "|"
                        + m.getLastProjectedEventAt()));
        sessionRevenue.findAll().stream()
                .sorted(Comparator.comparing(m -> m.getEventSessionId() + "|" + m.getCurrency()))
                .forEach(m -> lines.add("SR|" + m.getEventSessionId() + "|" + m.getEventId() + "|"
                        + m.getCurrency() + "|" + m.getPaymentsSucceeded() + "|"
                        + m.getGrossRevenueMinor() + "|" + m.getRefundsCompleted() + "|"
                        + m.getRefundedRevenueMinor() + "|" + m.getLastProjectedEventAt()));
        dailyOperational.findAll().stream()
                .sorted(Comparator.comparing(m -> m.getMetricDate() + "|" + m.getEventSessionId().toString()))
                .forEach(m -> lines.add("DO|" + m.getMetricDate() + "|" + m.getEventId() + "|"
                        + m.getEventSessionId() + "|" + m.getReservationsCreated() + "|"
                        + m.getReservationsConfirmed() + "|" + m.getReservationsExpired() + "|"
                        + m.getPaymentsSucceeded() + "|" + m.getPaymentsWithFailure() + "|"
                        + m.getRefundsCompleted() + "|" + m.getTicketsIssued() + "|"
                        + m.getTicketsRevoked() + "|" + m.getTicketsScanned()));
        dailyRevenue.findAll().stream()
                .sorted(Comparator.comparing(
                        m -> m.getMetricDate() + "|" + m.getEventSessionId().toString() + "|" + m.getCurrency()))
                .forEach(m -> lines.add("DR|" + m.getMetricDate() + "|" + m.getEventId() + "|"
                        + m.getEventSessionId() + "|" + m.getCurrency() + "|"
                        + m.getPaymentsSucceeded() + "|" + m.getGrossRevenueMinor() + "|"
                        + m.getRefundsCompleted() + "|" + m.getRefundedRevenueMinor()));
        return String.join("\n", lines);
    }

    private List<EventEnvelope<JsonNode>> canonicalHistory() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        Instant base = Instant.parse("2026-09-01T10:00:00Z");
        return List.of(
                held("canon-1", reservationId, eventId, sessionId, 2, base),
                completed("canon-2", paymentId, reservationId, sessionId, eventId, "150.00", "RON",
                        base.plusSeconds(60)),
                confirmed("canon-3", reservationId, eventId, sessionId, base.plusSeconds(61)),
                issued("canon-4", ticketId, reservationId, sessionId, eventId, base.plusSeconds(120)),
                scanned("canon-5", ticketId, reservationId, sessionId, base.plusSeconds(3600)));
    }

    // -- envelope factories (payload occurredAt == envelope time keeps tests deterministic) --

    private EventEnvelope<JsonNode> held(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId, int seatCount, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        var seatIds = payload.putArray("seatIds");
        for (int i = 0; i < seatCount; i++) {
            seatIds.add(UUID.randomUUID().toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationHeldEvent", at, payload);
    }

    private EventEnvelope<JsonNode> confirmed(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationConfirmedEvent", at, payload);
    }

    private EventEnvelope<JsonNode> expired(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationExpiredEvent", at, payload);
    }

    private EventEnvelope<JsonNode> cancelled(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationCancelledEvent", at, payload);
    }

    private EventEnvelope<JsonNode> reservationRefunded(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationRefunded", at, payload);
    }

    private EventEnvelope<JsonNode> completed(
            String eventId, UUID paymentId, UUID reservationId, UUID sessionId, UUID eventUuid,
            String amount, String currency, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("paymentId", paymentId.toString());
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("amount", amount);
        payload.put("currency", currency);
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "PaymentCompleted", at, payload);
    }

    private EventEnvelope<JsonNode> failed(
            String eventId, UUID paymentId, UUID reservationId, UUID sessionId, UUID eventUuid,
            Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("paymentId", paymentId.toString());
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "PaymentFailed", at, payload);
    }

    private EventEnvelope<JsonNode> paymentRefunded(
            String eventId, UUID paymentId, UUID reservationId, UUID sessionId, UUID eventUuid,
            String amount, String currency, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("paymentId", paymentId.toString());
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("amount", amount);
        payload.put("currency", currency);
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "PaymentRefunded", at, payload);
    }

    private EventEnvelope<JsonNode> issued(
            String eventId, UUID ticketId, UUID reservationId, UUID sessionId, UUID eventUuid,
            Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("ticketId", ticketId.toString());
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "TicketIssued", at, payload);
    }

    private EventEnvelope<JsonNode> scanned(
            String eventId, UUID ticketId, UUID reservationId, UUID sessionId, Instant at) {
        return scannedWithType(eventId, "TicketScanned", ticketId, reservationId, sessionId, at);
    }

    private EventEnvelope<JsonNode> scannedWithType(
            String eventId, String eventType, UUID ticketId, UUID reservationId, UUID sessionId,
            Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("ticketId", ticketId.toString());
        if (reservationId != null) {
            payload.put("reservationId", reservationId.toString());
        }
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, eventType, at, payload);
    }

    private EventEnvelope<JsonNode> batchRevoked(
            String eventId, UUID reservationId, UUID sessionId, UUID eventUuid, Instant at) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "TicketRevoked", at, payload);
    }

    private EventEnvelope<JsonNode> envelope(
            String eventId, String eventType, Instant at, ObjectNode payload) {
        return new EventEnvelope<>(eventId, eventType, at, "corr-1", null,
                UUID.randomUUID().toString(), 1, (JsonNode) payload);
    }
}
