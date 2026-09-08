package com.seatflow.analytics.service.impl;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.entity.EventSessionMetric;
import com.seatflow.analytics.model.entity.EventSessionRevenueMetric;
import com.seatflow.analytics.model.enums.AnalyticsSessionSort;
import com.seatflow.analytics.model.enums.AnalyticsTimeseriesMetric;
import com.seatflow.analytics.model.enums.AnalyticsTopMetric;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository.OperationalTotals;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository.RevenueTotal;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository.AttendanceCounts;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository.CohortCounts;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository.OccupancyInputs;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TASK-P14-004 service unit tests: validation allowlists, rate/money math, and gap filling
 * with mocked repositories (PostgreSQL grain behavior is covered by the integration test).
 */
@ExtendWith(MockitoExtension.class)
class AdminAnalyticsQueryServiceImplTest {

    static final UUID EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private AnalyticsAdminQueryRepository adminQueries;

    @Mock
    private AnalyticsProjectionQueryRepository projectionQueries;

    @Mock
    private AnalyticsSessionFactRepository sessionFactRepository;

    @Mock
    private EventSessionMetricRepository sessionMetricRepository;

    @Mock
    private EventSessionRevenueMetricRepository sessionRevenueRepository;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);

    private AdminAnalyticsQueryServiceImpl service;

    @BeforeEach
    void injectClock() {
        // @InjectMocks cannot resolve the final Clock field from a plain value; set it explicitly.
        service = new AdminAnalyticsQueryServiceImpl(
                adminQueries, projectionQueries, sessionFactRepository,
                sessionMetricRepository, sessionRevenueRepository, fixedClock);
    }

    private static AnalyticsDateRange range(String from, String to) {
        return new AnalyticsDateRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    private void stubEmptySummaryAggregates() {
        when(adminQueries.sumOperationalTotals(any(), any(), any(), any()))
                .thenReturn(OperationalTotals.zero());
        when(adminQueries.sumRevenueByCurrency(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(projectionQueries.conversionCohort(any(), any(), any(), any()))
                .thenReturn(new CohortCounts(0, 0));
        when(projectionQueries.expirationCohort(any(), any(), any(), any()))
                .thenReturn(new CohortCounts(0, 0));
        when(adminQueries.refundDenominator(any(), any(), any(), any())).thenReturn(0L);
        when(adminQueries.refundNumerator(any(), any(), any(), any())).thenReturn(0L);
        when(adminQueries.maxFactSourceTime()).thenReturn(null);
        when(adminQueries.maxProcessedAt()).thenReturn(null);
    }

    @Test
    void shouldReturnNullRatiosWhenCohortsAreEmpty() {
        stubEmptySummaryAggregates();

        var summary = service.getSummary(range("2026-08-08", "2026-09-06"), null, null);

        assertThat(summary.rates().reservationToPayment().ratio()).isNull();
        assertThat(summary.rates().expiration().ratio()).isNull();
        assertThat(summary.rates().refund().ratio()).isNull();
        assertThat(summary.rates().reservationToPayment().denominator()).isZero();
        assertThat(summary.freshness().eventuallyConsistent()).isTrue();
        assertThat(summary.freshness().generatedAt()).isEqualTo(Instant.parse("2026-09-06T12:00:00Z"));
        assertThat(summary.payments().revenueByCurrency()).isEmpty();
    }

    @Test
    void shouldComputeExactNetAndSixDecimalRates() {
        when(adminQueries.sumOperationalTotals(any(), any(), any(), any()))
                .thenReturn(OperationalTotals.zero());
        when(adminQueries.sumRevenueByCurrency(any(), any(), any(), any()))
                .thenReturn(List.of(new RevenueTotal("RON", 87, 4, 3_150_000L, 120_000L)));
        when(projectionQueries.conversionCohort(any(), any(), any(), any()))
                .thenReturn(new CohortCounts(120, 87));
        when(projectionQueries.expirationCohort(any(), any(), any(), any()))
                .thenReturn(new CohortCounts(120, 25));
        when(adminQueries.refundDenominator(any(), any(), any(), any())).thenReturn(87L);
        when(adminQueries.refundNumerator(any(), any(), any(), any())).thenReturn(4L);
        when(adminQueries.maxFactSourceTime()).thenReturn(Instant.parse("2026-09-06T11:59:42Z"));
        when(adminQueries.maxProcessedAt()).thenReturn(Instant.parse("2026-09-06T11:59:43Z"));

        var summary = service.getSummary(range("2026-08-08", "2026-09-06"), null, null);

        assertThat(summary.payments().revenueByCurrency()).hasSize(1);
        var ron = summary.payments().revenueByCurrency().getFirst();
        assertThat(ron.currency()).isEqualTo("RON");
        assertThat(ron.netMinor()).isEqualTo(3_030_000L);
        assertThat(ron.testMode()).isTrue();
        assertThat(summary.rates().reservationToPayment().ratio()).isEqualTo(new BigDecimal("0.725"));
        assertThat(summary.rates().expiration().ratio()).isEqualTo(new BigDecimal("0.208333"));
        assertThat(summary.rates().refund().ratio()).isEqualTo(new BigDecimal("0.045977"));
        assertThat(summary.freshness().lastProjectedEventAt())
                .isEqualTo(Instant.parse("2026-09-06T11:59:42Z"));
        assertThat(summary.freshness().lastProcessedAt())
                .isEqualTo(Instant.parse("2026-09-06T11:59:43Z"));
    }

    @Test
    void shouldRejectUnboundedPaginationAndLimits() {
        var range = range("2026-08-08", "2026-09-06");

        assertThatThrownBy(() -> service.getSessions(
                        range, null, -1, 25, AnalyticsSessionSort.STARTS_AT, true, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_LIMIT));
        assertThatThrownBy(() -> service.getSessions(
                        range, null, 0, 101, AnalyticsSessionSort.STARTS_AT, true, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_LIMIT));
        assertThatThrownBy(() -> service.getTop(
                        range, null, AnalyticsTopMetric.TICKETS_ISSUED, 21, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_LIMIT));
        assertThatThrownBy(() -> service.getTop(
                        range, null, AnalyticsTopMetric.TICKETS_ISSUED, 0, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_LIMIT));
    }

    @Test
    void shouldRequireCurrencyForMonetarySortAndRanking() {
        var range = range("2026-08-08", "2026-09-06");

        assertThatThrownBy(() -> service.getSessions(
                        range, null, 0, 25, AnalyticsSessionSort.GROSS_REVENUE, true, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_SORT));
        assertThatThrownBy(() -> service.getSessions(
                        range, null, 0, 25, AnalyticsSessionSort.GROSS_REVENUE, true, "EURO"))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_SORT));
        assertThatThrownBy(() -> service.getTop(
                        range, null, AnalyticsTopMetric.NET_REVENUE, 5, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_SORT));
    }

    @Test
    void shouldRejectPageOverflowAndNullMetrics() {
        // REV-007: pin the overflow guard and missing-metric 400s.
        var range = range("2026-08-08", "2026-09-06");

        assertThatThrownBy(() -> service.getSessions(
                        range, null, Integer.MAX_VALUE, 100, AnalyticsSessionSort.STARTS_AT, true, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_LIMIT));
        assertThatThrownBy(() -> service.getTop(
                        range, null, AnalyticsTopMetric.TICKETS_ISSUED, Integer.MAX_VALUE, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_LIMIT));
        assertThatThrownBy(() -> service.getTimeSeries(range, null, null, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_SORT));
        assertThatThrownBy(() -> service.getTop(range, null, null, 5, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ANALYTICS_SORT));
    }

    @Test
    void shouldFillMissingDatesWithZeroInSingleCountSeries() {
        var range = range("2026-09-04", "2026-09-06");
        when(adminQueries.findOperationalRows(any(), any(), any(), any())).thenReturn(List.of(
                operationalRow(LocalDate.of(2026, 9, 4), 10, 0, 0, 0),
                operationalRow(LocalDate.of(2026, 9, 6), 4, 0, 0, 0)));

        var response = service.getTimeSeries(
                range, null, null, AnalyticsTimeseriesMetric.TICKETS_ISSUED);

        assertThat(response.series()).hasSize(1);
        var points = response.series().getFirst().points();
        assertThat(points).hasSize(3);
        assertThat(points.get(0).value()).isEqualTo(10L);
        assertThat(points.get(1).value()).isZero();
        assertThat(points.get(2).value()).isEqualTo(4L);
        assertThat(response.series().getFirst().currency()).isNull();
    }

    @Test
    void shouldReturnOneSeriesPerCurrencyForMoney() {
        var range = range("2026-09-06", "2026-09-06");
        when(adminQueries.findRevenueRows(any(), any(), any(), any())).thenReturn(List.of(
                revenueRow(LocalDate.of(2026, 9, 6), "EUR", 500L, 0L),
                revenueRow(LocalDate.of(2026, 9, 6), "RON", 1_000L, 200L)));

        var response = service.getTimeSeries(
                range, null, null, AnalyticsTimeseriesMetric.NET_REVENUE);

        assertThat(response.series()).hasSize(2);
        assertThat(response.series().get(0).currency()).isEqualTo("EUR");
        assertThat(response.series().get(0).testMode()).isTrue();
        assertThat(response.series().get(0).points().getFirst().value()).isEqualTo(500L);
        assertThat(response.series().get(1).currency()).isEqualTo("RON");
        assertThat(response.series().get(1).points().getFirst().value()).isEqualTo(800L);
    }

    @Test
    void shouldThrow404ForUnknownSessionDetail() {
        when(sessionFactRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSessionDetail(SESSION_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void shouldComposeDetailWithoutCountDuplication() {
        AnalyticsSessionFact fact = AnalyticsSessionFact.builder()
                .eventSessionId(SESSION_ID).eventId(EVENT_ID)
                .eventTitle("Hamlet").sessionLabel("Evening")
                .lastSourceEventAt(Instant.parse("2026-09-06T11:00:00Z"))
                .updatedAt(Instant.parse("2026-09-06T11:00:00Z"))
                .build();
        EventSessionMetric metric = EventSessionMetric.builder()
                .eventSessionId(SESSION_ID).eventId(EVENT_ID)
                .reservationsCreated(10).reservationsConfirmed(8).reservationsExpired(1)
                .paymentsSucceeded(8).paymentsWithFailure(1).refundsCompleted(1)
                .ticketsIssued(16).ticketsRevoked(0).ticketsScanned(9)
                .lastProjectedEventAt(Instant.parse("2026-09-06T11:30:00Z"))
                .updatedAt(Instant.parse("2026-09-06T11:30:00Z"))
                .build();
        when(sessionFactRepository.findById(SESSION_ID)).thenReturn(Optional.of(fact));
        when(sessionMetricRepository.findById(SESSION_ID)).thenReturn(Optional.of(metric));
        when(sessionRevenueRepository.findByEventSessionId(SESSION_ID)).thenReturn(List.of(
                EventSessionRevenueMetric.builder().eventSessionId(SESSION_ID).eventId(EVENT_ID)
                        .currency("RON").paymentsSucceeded(5).refundsCompleted(1)
                        .grossRevenueMinor(1_000L).refundedRevenueMinor(200L)
                        .updatedAt(Instant.parse("2026-09-06T11:30:00Z")).build(),
                EventSessionRevenueMetric.builder().eventSessionId(SESSION_ID).eventId(EVENT_ID)
                        .currency("EUR").paymentsSucceeded(3).refundsCompleted(0)
                        .grossRevenueMinor(600L).refundedRevenueMinor(0L)
                        .updatedAt(Instant.parse("2026-09-06T11:30:00Z")).build()));
        when(projectionQueries.attendance(SESSION_ID)).thenReturn(new AttendanceCounts(16, 9));
        when(projectionQueries.occupancy(SESSION_ID)).thenReturn(new OccupancyInputs(100, 16));

        var detail = service.getSessionDetail(SESSION_ID);

        assertThat(detail.reservationsCreated()).isEqualTo(10L);
        assertThat(detail.ticketsIssued()).isEqualTo(16L);
        assertThat(detail.revenueByCurrency()).hasSize(2);
        assertThat(detail.revenueByCurrency().get(0).currency()).isEqualTo("EUR");
        assertThat(detail.revenueByCurrency().get(1).netMinor()).isEqualTo(800L);
        assertThat(detail.occupancyRatio()).isEqualTo(new BigDecimal("0.16"));
        assertThat(detail.attendanceRatio()).isEqualTo(new BigDecimal("0.5625"));
        assertThat(detail.lastProjectedEventAt())
                .isEqualTo(Instant.parse("2026-09-06T11:30:00Z"));
    }

    private static DailyOperationalMetric operationalRow(
            LocalDate date, long issued, long scanned, long created, long succeeded) {
        return DailyOperationalMetric.builder()
                .metricDate(date).eventId(EVENT_ID).eventSessionId(SESSION_ID)
                .reservationsCreated(created).reservationsConfirmed(0).reservationsExpired(0)
                .paymentsSucceeded(succeeded).paymentsWithFailure(0).refundsCompleted(0)
                .ticketsIssued(issued).ticketsRevoked(0).ticketsScanned(scanned)
                .updatedAt(Instant.parse("2026-09-06T12:00:00Z"))
                .build();
    }

    private static DailyRevenueMetric revenueRow(LocalDate date, String currency, long gross, long refunded) {
        return DailyRevenueMetric.builder()
                .metricDate(date).eventId(EVENT_ID).eventSessionId(SESSION_ID).currency(currency)
                .paymentsSucceeded(1).refundsCompleted(refunded > 0 ? 1 : 0)
                .grossRevenueMinor(gross).refundedRevenueMinor(refunded)
                .updatedAt(Instant.parse("2026-09-06T12:00:00Z"))
                .build();
    }
}
