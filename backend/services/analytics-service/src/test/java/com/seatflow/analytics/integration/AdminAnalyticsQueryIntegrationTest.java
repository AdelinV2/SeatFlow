package com.seatflow.analytics.integration;

import com.seatflow.analytics.model.entity.AnalyticsPaymentFact;
import com.seatflow.analytics.model.entity.AnalyticsReservationFact;
import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.AnalyticsTicketFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.entity.EventSessionMetric;
import com.seatflow.analytics.model.entity.EventSessionRevenueMetric;
import com.seatflow.analytics.model.entity.ProcessedEvent;
import com.seatflow.analytics.model.enums.AnalyticsSessionSort;
import com.seatflow.analytics.model.enums.AnalyticsTimeseriesMetric;
import com.seatflow.analytics.model.enums.AnalyticsTopMetric;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository;
import com.seatflow.analytics.repository.AnalyticsPaymentFactRepository;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository;
import com.seatflow.analytics.repository.AnalyticsReservationFactRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.DailyRevenueMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.repository.ProcessedEventRepository;
import com.seatflow.analytics.service.impl.AdminAnalyticsQueryServiceImpl;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TASK-P14-004 acceptance suite over real PostgreSQL.
 *
 * <p>Proves the aggregate-grain boundary end to end: one session with RON + EUR financial
 * rows contributes its operational counts exactly once while revenue stays currency-grouped.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        AnalyticsAdminQueryRepository.class,
        AnalyticsProjectionQueryRepository.class,
        AdminAnalyticsQueryServiceImpl.class,
        AdminAnalyticsQueryIntegrationTest.TestConfig.class
})
class AdminAnalyticsQueryIntegrationTest {

    static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID S1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID S2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID S3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID UNKNOWN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    static final LocalDate SEP_05 = LocalDate.of(2026, 9, 5);
    static final LocalDate SEP_06 = LocalDate.of(2026, 9, 6);
    static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_004_test")
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
    private AdminAnalyticsQueryServiceImpl service;

    @Autowired
    private Clock analyticsClock;

    @Autowired
    private DailyOperationalMetricRepository dailyOperational;

    @Autowired
    private DailyRevenueMetricRepository dailyRevenue;

    @Autowired
    private EventSessionMetricRepository sessionMetrics;

    @Autowired
    private EventSessionRevenueMetricRepository sessionRevenue;

    @Autowired
    private AnalyticsSessionFactRepository sessionFacts;

    @Autowired
    private AnalyticsReservationFactRepository reservationFacts;

    @Autowired
    private AnalyticsPaymentFactRepository paymentFacts;

    @Autowired
    private AnalyticsTicketFactRepository ticketFacts;

    @Autowired
    private ProcessedEventRepository processedEvents;

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @BeforeEach
    void seedFixtures() {
        // --- Session facts (S3 deliberately absent: missing title must stay null) ---
        sessionFacts.save(AnalyticsSessionFact.builder()
                .eventSessionId(S1).eventId(EVENT_ID)
                .eventTitle("Hamlet").sessionLabel("Evening")
                .startsAt(at("2026-09-06T19:00:00Z")).status("SCHEDULED")
                .capacitySnapshot(100)
                .lastSourceEventAt(at("2026-09-06T10:00:00Z")).updatedAt(NOW).build());
        sessionFacts.save(AnalyticsSessionFact.builder()
                .eventSessionId(S2).eventId(EVENT_ID)
                .eventTitle("Hamlet").sessionLabel("Matinee")
                .startsAt(at("2026-09-05T14:00:00Z")).status("SCHEDULED")
                .capacitySnapshot(null)
                .lastSourceEventAt(at("2026-09-05T10:00:00Z")).updatedAt(NOW).build());

        // --- Daily operational rows ---
        dailyOperational.save(operationalRow(SEP_06, S1, 10, 8, 1, 8, 1, 1, 16, 2, 9));
        dailyOperational.save(operationalRow(SEP_05, S1, 2, 1, 0, 0, 0, 0, 2, 0, 0));
        dailyOperational.save(operationalRow(SEP_06, S2, 1, 0, 0, 0, 0, 0, 16, 0, 0));
        dailyOperational.save(operationalRow(SEP_06, S3, 1, 0, 0, 0, 0, 0, 1, 0, 0));

        // --- Daily revenue rows: S1 owns RON + EUR on the same date ---
        dailyRevenue.save(revenueRow(SEP_06, S1, "RON", 5, 1, 1_000_000L, 100_000L));
        dailyRevenue.save(revenueRow(SEP_06, S1, "EUR", 3, 0, 500_000L, 0L));
        dailyRevenue.save(revenueRow(SEP_06, S2, "RON", 2, 0, 200_000L, 0L));

        // --- Reservation facts for P14-003 cohorts (created cohort of 4) ---
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        reservationFacts.save(reservationFact(r1, at("2026-09-01T10:00:00Z"),
                at("2026-09-01T11:00:00Z"), null, null));
        reservationFacts.save(reservationFact(r2, at("2026-09-02T10:00:00Z"),
                at("2026-09-02T11:00:00Z"), null, at("2026-09-03T12:00:00Z")));
        reservationFacts.save(reservationFact(UUID.randomUUID(), at("2026-09-03T10:00:00Z"),
                null, at("2026-09-04T10:00:00Z"), null));
        reservationFacts.save(reservationFact(UUID.randomUUID(), at("2026-09-04T10:00:00Z"),
                null, null, null));

        // --- Payment facts: 2 succeeded, 1 refunded ---
        paymentFacts.save(AnalyticsPaymentFact.builder()
                .paymentId(UUID.randomUUID()).reservationId(r1).eventSessionId(S1)
                .latestStatus("COMPLETED").currency("RON")
                .completedAmountMinor(1_000L).refundedAmountMinor(0L)
                .completedAt(at("2026-09-01T11:00:00Z"))
                .lastSourceEventAt(at("2026-09-01T11:00:00Z")).updatedAt(NOW).build());
        paymentFacts.save(AnalyticsPaymentFact.builder()
                .paymentId(UUID.randomUUID()).reservationId(r2).eventSessionId(S1)
                .latestStatus("REFUNDED").currency("RON")
                .completedAmountMinor(500L).refundedAmountMinor(500L)
                .completedAt(at("2026-09-02T11:00:00Z")).refundedAt(at("2026-09-03T11:00:00Z"))
                .lastSourceEventAt(at("2026-09-03T11:00:00Z")).updatedAt(NOW).build());

        // --- Ticket facts: 2 eligible issued, 1 scanned ---
        ticketFacts.save(AnalyticsTicketFact.builder()
                .ticketId(UUID.randomUUID()).reservationId(r1).eventSessionId(S1)
                .issuedAt(at("2026-09-01T11:00:00Z")).firstScannedAt(at("2026-09-02T19:00:00Z"))
                .status("ISSUED").lastSourceEventAt(at("2026-09-02T19:00:00Z")).updatedAt(NOW).build());
        ticketFacts.save(AnalyticsTicketFact.builder()
                .ticketId(UUID.randomUUID()).reservationId(r1).eventSessionId(S1)
                .issuedAt(at("2026-09-01T11:00:00Z")).revokedAt(at("2026-09-03T10:00:00Z"))
                .status("REVOKED").lastSourceEventAt(at("2026-09-03T10:00:00Z")).updatedAt(NOW).build());
        ticketFacts.save(AnalyticsTicketFact.builder()
                .ticketId(UUID.randomUUID()).reservationId(r2).eventSessionId(S1)
                .issuedAt(at("2026-09-02T11:00:00Z"))
                .status("ISSUED").lastSourceEventAt(at("2026-09-02T11:00:00Z")).updatedAt(NOW).build());

        // --- Lifetime aggregates for the detail endpoint ---
        sessionMetrics.save(EventSessionMetric.builder()
                .eventSessionId(S1).eventId(EVENT_ID)
                .reservationsCreated(12).reservationsConfirmed(9).reservationsExpired(1)
                .paymentsSucceeded(8).paymentsWithFailure(1).refundsCompleted(1)
                .ticketsIssued(18).ticketsRevoked(2).ticketsScanned(9)
                .lastProjectedEventAt(at("2026-09-06T11:30:00Z")).updatedAt(NOW).build());
        sessionRevenue.save(EventSessionRevenueMetric.builder()
                .eventSessionId(S1).eventId(EVENT_ID).currency("RON")
                .paymentsSucceeded(5).refundsCompleted(1)
                .grossRevenueMinor(1_000_000L).refundedRevenueMinor(100_000L)
                .updatedAt(NOW).build());
        sessionRevenue.save(EventSessionRevenueMetric.builder()
                .eventSessionId(S1).eventId(EVENT_ID).currency("EUR")
                .paymentsSucceeded(3).refundsCompleted(0)
                .grossRevenueMinor(500_000L).refundedRevenueMinor(0L)
                .updatedAt(NOW).build());

        // --- Processed events for freshness ---
        processedEvents.save(ProcessedEvent.builder()
                .eventId("evt-1").eventType("ReservationHeld").sourceTopic("reservation.events")
                .occurredAt(at("2026-09-06T11:59:42Z")).processedAt(at("2026-09-06T11:59:43Z")).build());
        processedEvents.save(ProcessedEvent.builder()
                .eventId("evt-0").eventType("ReservationHeld").sourceTopic("reservation.events")
                .occurredAt(at("2026-09-05T10:00:00Z")).processedAt(at("2026-09-05T10:00:01Z")).build());
    }

    private static DailyOperationalMetric operationalRow(
            LocalDate date, UUID session,
            long created, long confirmed, long expired,
            long payOk, long payFail, long refunds,
            long issued, long revoked, long scanned) {
        return operationalRowFor(EVENT_ID, date, session,
                created, confirmed, expired, payOk, payFail, refunds, issued, revoked, scanned);
    }

    private static DailyOperationalMetric operationalRowFor(
            UUID eventId, LocalDate date, UUID session,
            long created, long confirmed, long expired,
            long payOk, long payFail, long refunds,
            long issued, long revoked, long scanned) {
        return DailyOperationalMetric.builder()
                .metricDate(date).eventId(eventId).eventSessionId(session)
                .reservationsCreated(created).reservationsConfirmed(confirmed).reservationsExpired(expired)
                .paymentsSucceeded(payOk).paymentsWithFailure(payFail).refundsCompleted(refunds)
                .ticketsIssued(issued).ticketsRevoked(revoked).ticketsScanned(scanned)
                .updatedAt(NOW).build();
    }

    private static DailyRevenueMetric revenueRow(
            LocalDate date, UUID session, String currency,
            long payOk, long refunds, long gross, long refunded) {
        return revenueRowFor(EVENT_ID, date, session, currency, payOk, refunds, gross, refunded);
    }

    private static DailyRevenueMetric revenueRowFor(
            UUID eventId, LocalDate date, UUID session, String currency,
            long payOk, long refunds, long gross, long refunded) {
        return DailyRevenueMetric.builder()
                .metricDate(date).eventId(eventId).eventSessionId(session).currency(currency)
                .paymentsSucceeded(payOk).refundsCompleted(refunds)
                .grossRevenueMinor(gross).refundedRevenueMinor(refunded)
                .updatedAt(NOW).build();
    }

    private static AnalyticsReservationFact reservationFact(
            UUID reservationId, Instant created, Instant confirmed, Instant expired, Instant refunded) {
        return reservationFactFor(S1, reservationId, created, confirmed, expired, refunded);
    }

    private static AnalyticsReservationFact reservationFactFor(
            UUID sessionId, UUID reservationId, Instant created, Instant confirmed, Instant expired,
            Instant refunded) {
        return AnalyticsReservationFact.builder()
                .reservationId(reservationId).eventId(EVENT_ID).eventSessionId(sessionId)
                .createdAt(created).confirmedAt(confirmed).expiredAt(expired).refundedAt(refunded)
                .seatCount(2)
                .lastSourceEventAt(refunded != null ? refunded
                        : expired != null ? expired : confirmed != null ? confirmed : created)
                .updatedAt(NOW).build();
    }

    private AnalyticsDateRange defaultRange() {
        return AnalyticsDateRange.resolve(null, null, analyticsClock);
    }

    // ------------------------------------------------------------------
    // Summary: currency-neutral counts, grouped money, P14-003 cohorts
    // ------------------------------------------------------------------

    @Test
    @DisplayName("summary counts each session once despite RON+EUR revenue rows")
    void shouldNotDuplicateCountsAcrossCurrencies() {
        var summary = service.getSummary(defaultRange(), null, null);

        assertThat(summary.from()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(summary.to()).isEqualTo(SEP_06);
        assertThat(summary.reservations().created()).isEqualTo(14L);
        assertThat(summary.reservations().confirmed()).isEqualTo(9L);
        assertThat(summary.reservations().expired()).isEqualTo(1L);
        assertThat(summary.reservations().refunded()).isEqualTo(1L);
        assertThat(summary.tickets().issued()).isEqualTo(35L);
        assertThat(summary.tickets().revoked()).isEqualTo(2L);
        assertThat(summary.tickets().scanned()).isEqualTo(9L);
        assertThat(summary.payments().succeeded()).isEqualTo(8L);
        assertThat(summary.payments().withFailure()).isEqualTo(1L);

        assertThat(summary.payments().revenueByCurrency()).hasSize(2);
        var eur = summary.payments().revenueByCurrency().get(0);
        var ron = summary.payments().revenueByCurrency().get(1);
        assertThat(eur.currency()).isEqualTo("EUR");
        assertThat(eur.netMinor()).isEqualTo(500_000L);
        assertThat(ron.currency()).isEqualTo("RON");
        assertThat(ron.grossMinor()).isEqualTo(1_200_000L);
        assertThat(ron.refundedMinor()).isEqualTo(100_000L);
        assertThat(ron.netMinor()).isEqualTo(1_100_000L);
        assertThat(ron.testMode()).isTrue();

        assertThat(summary.rates().reservationToPayment().ratio()).isEqualTo(new BigDecimal("0.5"));
        assertThat(summary.rates().expiration().ratio()).isEqualTo(new BigDecimal("0.25"));
        assertThat(summary.rates().refund().ratio()).isEqualTo(new BigDecimal("0.5"));

        assertThat(summary.freshness().eventuallyConsistent()).isTrue();
        assertThat(summary.freshness().generatedAt()).isEqualTo(NOW);
        assertThat(summary.freshness().lastProjectedEventAt()).isEqualTo(at("2026-09-06T10:00:00Z"));
        assertThat(summary.freshness().lastProcessedAt()).isEqualTo(at("2026-09-06T11:59:43Z"));
    }

    @Test
    @DisplayName("empty valid range returns zeros, not 404")
    void shouldReturnZerosForEmptyRange() {
        var range = new AnalyticsDateRange(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2));

        var summary = service.getSummary(range, null, null);

        assertThat(summary.reservations().created()).isZero();
        assertThat(summary.payments().revenueByCurrency()).isEmpty();
        assertThat(summary.rates().refund().ratio()).isNull();
        assertThat(service.getSessions(range, null, 0, 25, AnalyticsSessionSort.STARTS_AT, true, null)
                .totalElements()).isZero();
        assertThat(service.getTop(range, null, AnalyticsTopMetric.TICKETS_ISSUED, 5, null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Time series
    // ------------------------------------------------------------------

    @Test
    @DisplayName("count series is single and zero-filled; money series split per currency")
    void shouldBuildStableTimeSeries() {
        var range = new AnalyticsDateRange(LocalDate.of(2026, 9, 4), SEP_06);

        var counts = service.getTimeSeries(range, null, null, AnalyticsTimeseriesMetric.TICKETS_ISSUED);
        assertThat(counts.series()).hasSize(1);
        var points = counts.series().getFirst().points();
        assertThat(points).hasSize(3);
        assertThat(points.get(0).value()).isZero();
        assertThat(points.get(1).value()).isEqualTo(2L);
        assertThat(points.get(2).value()).isEqualTo(33L);

        var money = service.getTimeSeries(range, null, null, AnalyticsTimeseriesMetric.GROSS_REVENUE);
        assertThat(money.series()).hasSize(2);
        assertThat(money.series().get(0).currency()).isEqualTo("EUR");
        assertThat(money.series().get(0).points().stream().mapToLong(p -> p.value()).toArray())
                .containsExactly(0L, 0L, 500_000L);
        assertThat(money.series().get(1).currency()).isEqualTo("RON");
        assertThat(money.series().get(1).points().stream().mapToLong(p -> p.value()).toArray())
                .containsExactly(0L, 0L, 1_200_000L);
    }

    // ------------------------------------------------------------------
    // Sessions: paging, enrichment, ordering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("session page keeps totalElements stable and enriches revenue in batch")
    void shouldPageSessionsWithBatchedRevenue() {
        var range = new AnalyticsDateRange(SEP_06, SEP_06);

        var page = service.getSessions(range, EVENT_ID, 0, 25, AnalyticsSessionSort.STARTS_AT, true, null);

        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.content()).hasSize(3);
        // startsAt DESC: S1 (Sep 6 19:00), S2 (Sep 5 14:00), S3 (unknown, last).
        assertThat(page.content().get(0).eventSessionId()).isEqualTo(S1);
        assertThat(page.content().get(1).eventSessionId()).isEqualTo(S2);
        assertThat(page.content().get(2).eventSessionId()).isEqualTo(S3);

        var first = page.content().getFirst();
        assertThat(first.eventTitle()).isEqualTo("Hamlet");
        assertThat(first.reservationsCreated()).isEqualTo(10L);
        assertThat(first.ticketsIssued()).isEqualTo(16L);
        assertThat(first.revenueByCurrency()).hasSize(2);
        assertThat(first.revenueByCurrency().get(0).currency()).isEqualTo("EUR");
        assertThat(first.revenueByCurrency().get(1).netMinor()).isEqualTo(900_000L);

        var last = page.content().get(2);
        assertThat(last.eventTitle()).isNull();
        assertThat(last.sessionLabel()).isNull();
        assertThat(last.revenueByCurrency()).isEmpty();
        assertThat(last.occupancyRatio()).isNull();
    }

    @Test
    @DisplayName("grossRevenue sort orders by requested-currency net without dropping sessions")
    void shouldSortSessionsByCurrencyRevenue() {
        var range = new AnalyticsDateRange(SEP_06, SEP_06);

        var page = service.getSessions(range, null, 0, 25, AnalyticsSessionSort.GROSS_REVENUE, true, "ron");

        assertThat(page.totalElements()).isEqualTo(3L);
        assertThat(page.content().stream().map(r -> r.eventSessionId()).toList())
                .containsExactly(S1, S2, S3);
    }

    @Test
    @DisplayName("eventSessionId may be supplied without eventId; unknown pair yields empty data")
    void shouldAcceptSessionFilterWithoutEvent() {
        var range = new AnalyticsDateRange(SEP_06, SEP_06);

        var summary = service.getSummary(range, null, S1);
        assertThat(summary.reservations().created()).isEqualTo(10L);
        assertThat(summary.payments().revenueByCurrency()).hasSize(2);

        var missing = service.getSummary(range, UUID.randomUUID(), UNKNOWN);
        assertThat(missing.reservations().created()).isZero();
        assertThat(missing.payments().revenueByCurrency()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Detail + top
    // ------------------------------------------------------------------

    @Test
    @DisplayName("detail returns lifetime aggregates with exact ratios; unknown session is 404")
    void shouldReturnSessionDetail() {
        var detail = service.getSessionDetail(S1);

        assertThat(detail.eventTitle()).isEqualTo("Hamlet");
        assertThat(detail.reservationsCreated()).isEqualTo(12L);
        assertThat(detail.ticketsIssued()).isEqualTo(18L);
        assertThat(detail.revenueByCurrency()).hasSize(2);
        assertThat(detail.occupancyRatio()).isEqualTo(new BigDecimal("0.02"));
        assertThat(detail.attendanceRatio()).isEqualTo(new BigDecimal("0.5"));
        assertThat(detail.lastProjectedEventAt()).isEqualTo(at("2026-09-06T11:30:00Z"));

        assertThatThrownBy(() -> service.getSessionDetail(UNKNOWN))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("top breaks ties by session id and requires currency for NET_REVENUE")
    void shouldRankTopDeterministically() {
        var range = new AnalyticsDateRange(SEP_06, SEP_06);

        var top = service.getTop(range, null, AnalyticsTopMetric.TICKETS_ISSUED, 2, null);
        assertThat(top).hasSize(2);
        assertThat(top.get(0).eventSessionId()).isEqualTo(S1);
        assertThat(top.get(0).value()).isEqualTo(16L);
        assertThat(top.get(1).eventSessionId()).isEqualTo(S2);
        assertThat(top.get(1).currency()).isNull();

        var revenue = service.getTop(range, null, AnalyticsTopMetric.NET_REVENUE, 5, "RON");
        assertThat(revenue.getFirst().eventSessionId()).isEqualTo(S1);
        assertThat(revenue.getFirst().value()).isEqualTo(900_000L);
        assertThat(revenue.getFirst().currency()).isEqualTo("RON");

        assertThatThrownBy(() -> service.getTop(range, null, AnalyticsTopMetric.NET_REVENUE, 5, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_SORT));
    }

    @Test
    @DisplayName("REV-002: grossRevenue sort orders by gross while NET_REVENUE ranks by net")
    void shouldDistinguishGrossSortFromNetRanking() {
        UUID event9 = UUID.fromString("99999999-0000-0000-0000-000000000009");
        UUID sa = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
        UUID sb = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
        // SA: high gross, heavy refunds (net 100k). SB: lower gross, no refunds (net 200k).
        dailyOperational.save(operationalRowFor(event9, SEP_06, sa, 1, 0, 0, 0, 0, 0, 1, 0, 0));
        dailyOperational.save(operationalRowFor(event9, SEP_06, sb, 1, 0, 0, 0, 0, 0, 1, 0, 0));
        dailyRevenue.save(revenueRowFor(event9, SEP_06, sa, "RON", 5, 1, 1_000_000L, 900_000L));
        dailyRevenue.save(revenueRowFor(event9, SEP_06, sb, "RON", 2, 0, 200_000L, 0L));

        var range = new AnalyticsDateRange(SEP_06, SEP_06);
        var listed = service.getSessions(
                range, event9, 0, 25, AnalyticsSessionSort.GROSS_REVENUE, true, "RON");
        assertThat(listed.content().stream().map(r -> r.eventSessionId()).toList())
                .containsExactly(sa, sb);

        var ranked = service.getTop(range, event9, AnalyticsTopMetric.NET_REVENUE, 5, "RON");
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).eventSessionId()).isEqualTo(sb);
        assertThat(ranked.get(0).value()).isEqualTo(200_000L);
        assertThat(ranked.get(1).eventSessionId()).isEqualTo(sa);
        assertThat(ranked.get(1).value()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("REV-003: event-scoped refund rate includes payments on sessions without facts")
    void shouldIncludeFactLessSessionPaymentsInEventScopedRefundRate() {
        // S3 has daily rows but deliberately no session fact. Its payment must still count
        // in the event-scoped refund cohort via its reservation fact.
        UUID r5 = UUID.fromString("55555555-5555-5555-5555-555555555555");
        reservationFacts.save(reservationFactFor(
                S3, r5, at("2026-09-04T10:00:00Z"), at("2026-09-04T11:00:00Z"), null, null));
        paymentFacts.save(AnalyticsPaymentFact.builder()
                .paymentId(UUID.fromString("66666666-6666-6666-6666-666666666666"))
                .reservationId(r5).eventSessionId(S3)
                .latestStatus("REFUNDED").currency("RON")
                .completedAmountMinor(700L).refundedAmountMinor(700L)
                .completedAt(at("2026-09-04T11:00:00Z")).refundedAt(at("2026-09-05T11:00:00Z"))
                .lastSourceEventAt(at("2026-09-05T11:00:00Z")).updatedAt(NOW).build());

        var scoped = service.getSummary(defaultRange(), EVENT_ID, null);
        assertThat(scoped.rates().refund().denominator()).isEqualTo(3L);
        assertThat(scoped.rates().refund().numerator()).isEqualTo(2L);
        assertThat(scoped.rates().refund().ratio()).isEqualTo(new BigDecimal("0.666667"));

        var unscoped = service.getSummary(defaultRange(), null, null);
        assertThat(unscoped.rates().refund().denominator()).isEqualTo(3L);
        assertThat(unscoped.rates().refund().numerator()).isEqualTo(2L);
    }
}
