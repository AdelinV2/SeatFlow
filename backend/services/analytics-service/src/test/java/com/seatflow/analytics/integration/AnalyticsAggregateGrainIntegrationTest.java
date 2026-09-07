package com.seatflow.analytics.integration;

import com.seatflow.analytics.messaging.AnalyticsEventValidationException;
import com.seatflow.analytics.messaging.ProjectionEventProcessor;
import com.seatflow.analytics.model.enums.AnalyticsSessionSort;
import com.seatflow.analytics.model.enums.AnalyticsTopMetric;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository;
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
import com.seatflow.analytics.service.impl.AdminAnalyticsQueryServiceImpl;
import com.seatflow.analytics.support.AnalyticsCanonicalFixtures;
import com.seatflow.analytics.support.AnalyticsEnvelopeFactory;
import com.seatflow.analytics.support.AnalyticsProjectionTestConfig;
import com.seatflow.analytics.support.AnalyticsSnapshot;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TASK-P14-007 sections 7.9-7.10 acceptance suite over real PostgreSQL.
 *
 * <p>Proves financial correctness in exact integer minor units (success, failure, full refund,
 * duplicate refund, oversize refund, pending/provisional refund, currency separation) and the
 * operational-vs-financial aggregate grain: the same-session S4 RON+EUR fixture yields exactly
 * one operational row plus two currency-keyed revenue rows, with API counts unaffected by the
 * currency join.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        AnalyticsProjectionTestConfig.class,
        AnalyticsAdminQueryRepository.class,
        AdminAnalyticsQueryServiceImpl.class,
        AnalyticsAggregateGrainIntegrationTest.TestConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalyticsAggregateGrainIntegrationTest {

    static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    static final LocalDate SEP_06 = LocalDate.of(2026, 9, 6);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_007_grain_test")
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
        @Primary
        Clock analyticsClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private ProjectionEventProcessor processor;

    @Autowired
    private AdminAnalyticsQueryServiceImpl queryService;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    // 7.9 Financial / refund correctness
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.9 successful completion records exact gross minor units")
    void successRecordsExactGross() {
        driver.processAll(AnalyticsCanonicalFixtures.s3History());

        var revenue = sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S3);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().getCurrency()).isEqualTo("EUR");
        assertThat(revenue.getFirst().getGrossRevenueMinor()).isEqualTo(5_000L);
        assertThat(revenue.getFirst().getRefundedRevenueMinor()).isZero();
    }

    @Test
    @DisplayName("7.9 failure evidence leaves gross unchanged and counts payments_with_failure")
    void failureLeavesGrossUnchanged() {
        driver.processAll(AnalyticsCanonicalFixtures.failureHistory());

        assertThat(sessionRevenue.findAll()).isEmpty();
        assertThat(dailyRevenue.findAll()).isEmpty();
        var metrics = sessionMetrics.findById(AnalyticsCanonicalFixtures.S5).orElseThrow();
        assertThat(metrics.getPaymentsWithFailure()).isOne();
        assertThat(metrics.getPaymentsSucceeded()).isZero();
    }

    @Test
    @DisplayName("7.9 completed full refund keeps gross, records refunded, nets to zero")
    void fullRefundNetsToZero() {
        driver.processAll(AnalyticsCanonicalFixtures.s1History());

        var revenue = sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S1);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().getGrossRevenueMinor()).isEqualTo(20_000L);
        assertThat(revenue.getFirst().getRefundedRevenueMinor()).isEqualTo(20_000L);
        assertThat(revenue.getFirst().netRevenueMinor()).isZero();
        assertThat(sessionMetrics.findById(AnalyticsCanonicalFixtures.S1).orElseThrow()
                .getRefundsCompleted()).isOne();
    }

    @Test
    @DisplayName("7.9 duplicate refund delivery changes nothing")
    void duplicateRefundIsNoOp() {
        var history = AnalyticsCanonicalFixtures.s1History();
        history.forEach(e ->
                assertThat(driver.process(e)).isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED));
        String first = snapshot();

        var refund = history.get(6);
        assertThat(driver.process(refund)).isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);
    }

    @Test
    @DisplayName("7.9 oversize refund is rejected: never clamped, never negative, no marker")
    void oversizeRefundIsRejected() {
        UUID session = UUID.fromString("91111111-1111-4111-8111-111111111111");
        UUID reservation = UUID.fromString("9a111111-1111-4111-8111-111111111111");
        UUID payment = UUID.fromString("9b111111-1111-4111-8111-111111111111");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        driver.process(AnalyticsEnvelopeFactory.held(
                "grain-over-held", reservation, AnalyticsCanonicalFixtures.EVENT_E1, session,
                1, base));
        driver.process(AnalyticsEnvelopeFactory.completed(
                "grain-over-pay", payment, reservation, session, AnalyticsCanonicalFixtures.EVENT_E1,
                "100.00", "RON", base.plusSeconds(10)));

        var oversized = AnalyticsEnvelopeFactory.paymentRefunded(
                "grain-over-refund", payment, reservation, session, AnalyticsCanonicalFixtures.EVENT_E1,
                "100.01", "RON", base.plusSeconds(20));
        assertThatThrownBy(() -> driver.process(oversized))
                .isInstanceOf(AnalyticsEventValidationException.class);

        // Rejected: no marker (claim rolled back), gross preserved, no negative net.
        assertThat(markerCount("grain-over-refund")).isZero();
        var revenue = sessionRevenue.findByEventSessionId(session);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().getGrossRevenueMinor()).isEqualTo(10_000L);
        assertThat(revenue.getFirst().getRefundedRevenueMinor()).isZero();
        assertThat(revenue.getFirst().netRevenueMinor()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("7.9 refund-first provisional fact creates no financial aggregate")
    void provisionalRefundCreatesNoAggregate() {
        UUID session = UUID.fromString("92222222-2222-4222-8222-222222222222");
        UUID reservation = UUID.fromString("9a222222-2222-4222-8222-222222222222");
        UUID payment = UUID.fromString("9b222222-2222-4222-8222-222222222222");
        Instant base = Instant.parse("2026-09-05T10:00:00Z");

        driver.process(AnalyticsEnvelopeFactory.held(
                "grain-prov-held", reservation, AnalyticsCanonicalFixtures.EVENT_E1, session,
                1, base));
        driver.process(AnalyticsEnvelopeFactory.paymentRefunded(
                "grain-prov-refund", payment, reservation, session, AnalyticsCanonicalFixtures.EVENT_E1,
                "100.00", "RON", base.plusSeconds(30)));

        assertThat(sessionRevenue.findAll()).isEmpty();
        assertThat(dailyRevenue.findAll()).isEmpty();
        assertThat(sessionMetrics.findById(session).orElseThrow().getRefundsCompleted()).isZero();
    }

    @Test
    @DisplayName("7.9 RON and EUR stay separate with no mixed-currency total")
    void currenciesStaySeparate() {
        driver.processAll(AnalyticsCanonicalFixtures.s4History());

        var revenues = sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S4);
        assertThat(revenues).hasSize(2);
        var ron = revenues.stream().filter(r -> r.getCurrency().equals("RON")).findFirst().orElseThrow();
        var eur = revenues.stream().filter(r -> r.getCurrency().equals("EUR")).findFirst().orElseThrow();
        assertThat(ron.getGrossRevenueMinor()).isEqualTo(10_000L);
        assertThat(eur.getGrossRevenueMinor()).isEqualTo(2_000L);
        // No mixed-currency 12000 total exists anywhere.
        assertThat(revenues.stream().mapToLong(r -> r.getGrossRevenueMinor()).sum()).isEqualTo(12_000L);
        assertThat(revenues.stream().map(r -> r.getCurrency()).distinct().count()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // 7.10 Operational-vs-financial aggregate grain
    // ------------------------------------------------------------------

    @Test
    @DisplayName("7.10 S4 persists one operational row and two currency revenue rows")
    void s4GrainPersisted() {
        driver.processAll(AnalyticsCanonicalFixtures.s4History());

        assertThat(sessionMetrics.findById(AnalyticsCanonicalFixtures.S4)).isPresent();
        var operational = sessionMetrics.findById(AnalyticsCanonicalFixtures.S4).orElseThrow();
        assertThat(operational.getReservationsCreated()).isEqualTo(2L);
        assertThat(operational.getReservationsConfirmed()).isEqualTo(2L);
        assertThat(operational.getPaymentsSucceeded()).isEqualTo(2L);
        assertThat(operational.getTicketsIssued()).isEqualTo(2L);

        // Exactly one operational row for S4 (not one per currency).
        assertThat(sessionMetrics.findAll().stream()
                .filter(m -> m.getEventSessionId().equals(AnalyticsCanonicalFixtures.S4))
                .count()).isOne();
        assertThat(sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S4)).hasSize(2);

        // Exactly one daily operational row for the shared S4 date grain.
        var dailyOps = dailyOperational.findAll().stream()
                .filter(m -> m.getEventSessionId().equals(AnalyticsCanonicalFixtures.S4)).toList();
        assertThat(dailyOps).hasSize(1);
        assertThat(dailyOps.getFirst().getReservationsCreated()).isEqualTo(2L);

        var dailyRev = dailyRevenue.findAll().stream()
                .filter(m -> m.getEventSessionId().equals(AnalyticsCanonicalFixtures.S4)).toList();
        assertThat(dailyRev).hasSize(2);
    }

    @Test
    @DisplayName("7.10 API sessions page lists S4 once with stable totalElements")
    void s4ApiSessionsNotMultiplied() {
        driver.processAll(AnalyticsCanonicalFixtures.s4History());
        var range = new AnalyticsDateRange(SEP_06, SEP_06);

        var page = queryService.getSessions(
                range, AnalyticsCanonicalFixtures.EVENT_E3, 0, 25,
                AnalyticsSessionSort.STARTS_AT, true, null);

        assertThat(page.totalElements()).isOne();
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().eventSessionId())
                .isEqualTo(AnalyticsCanonicalFixtures.S4);
        assertThat(page.content().getFirst().revenueByCurrency()).hasSize(2);
        assertThat(page.content().getFirst().reservationsCreated()).isEqualTo(2L);
        assertThat(page.content().getFirst().ticketsIssued()).isEqualTo(2L);
    }

    @Test
    @DisplayName("7.10 summary counts are currency-neutral with per-currency revenue arrays")
    void s4SummaryCountsNeutral() {
        driver.processAll(AnalyticsCanonicalFixtures.s4History());
        var range = new AnalyticsDateRange(SEP_06, SEP_06);

        var summary = queryService.getSummary(range, AnalyticsCanonicalFixtures.EVENT_E3, null);

        assertThat(summary.reservations().created()).isEqualTo(2L);
        assertThat(summary.reservations().confirmed()).isEqualTo(2L);
        assertThat(summary.tickets().issued()).isEqualTo(2L);
        assertThat(summary.payments().succeeded()).isEqualTo(2L);
        assertThat(summary.payments().revenueByCurrency()).hasSize(2);
        // Zero refunds over a nonzero payment cohort is an exact zero rate (the null-ratio
        // zero-denominator case is proven by the empty-range API test instead).
        assertThat(summary.rates().refund().ratio()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("7.10 financial sort and top ranking require one explicit currency")
    void financialSortRequiresCurrency() {
        driver.processAll(AnalyticsCanonicalFixtures.s4History());
        var range = new AnalyticsDateRange(SEP_06, SEP_06);

        assertThatThrownBy(() -> queryService.getSessions(
                        range, null, 0, 25, AnalyticsSessionSort.GROSS_REVENUE, true, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_SORT));

        assertThatThrownBy(() -> queryService.getTop(
                        range, null, AnalyticsTopMetric.NET_REVENUE, 5, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_SORT));

        var page = queryService.getSessions(
                range, null, 0, 25, AnalyticsSessionSort.GROSS_REVENUE, true, "RON");
        assertThat(page.totalElements()).isOne();

        var top = queryService.getTop(range, null, AnalyticsTopMetric.NET_REVENUE, 5, "RON");
        assertThat(top).hasSize(1);
        assertThat(top.getFirst().currency()).isEqualTo("RON");
        assertThat(top.getFirst().value()).isEqualTo(10_000L);
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
