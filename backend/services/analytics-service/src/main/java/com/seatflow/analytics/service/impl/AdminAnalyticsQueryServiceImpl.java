package com.seatflow.analytics.service.impl;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.entity.EventSessionMetric;
import com.seatflow.analytics.model.enums.AnalyticsSessionSort;
import com.seatflow.analytics.model.enums.AnalyticsTimeseriesMetric;
import com.seatflow.analytics.model.enums.AnalyticsTopMetric;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository.OperationalTotals;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository.AttendanceCounts;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository.CohortCounts;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository.OccupancyInputs;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.service.AdminAnalyticsQueryService;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.analytics.web.dto.response.AnalyticsDailyPointResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsSummaryResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsTimeSeriesResponse;
import com.seatflow.analytics.web.dto.response.EventSessionAnalyticsResponse;
import com.seatflow.analytics.web.dto.response.MoneyMetricResponse;
import com.seatflow.analytics.web.dto.response.ProjectionFreshnessResponse;
import com.seatflow.analytics.web.dto.response.RateMetricResponse;
import com.seatflow.analytics.web.dto.response.TopAnalyticsItemResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Admin analytics read implementation (TASK-P14-004).
 *
 * <p>Grain guarantees: operational counts are summed once from currency-neutral
 * {@code daily_operational_metrics}; revenue is grouped by currency from
 * {@code daily_revenue_metrics}. The two are never joined before aggregation, so one
 * session with RON + EUR activity contributes its counts exactly once. Rates reuse the
 * P14-003 cohort definitions in {@link AnalyticsProjectionQueryRepository}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminAnalyticsQueryServiceImpl implements AdminAnalyticsQueryService {

    /** Maximum page size for session listings. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Maximum limit for top rankings. */
    public static final int MAX_TOP_LIMIT = 20;

    private final AnalyticsAdminQueryRepository adminQueries;
    private final AnalyticsProjectionQueryRepository projectionQueries;
    private final AnalyticsSessionFactRepository sessionFactRepository;
    private final EventSessionMetricRepository sessionMetricRepository;
    private final EventSessionRevenueMetricRepository sessionRevenueRepository;
    private final Clock analyticsClock;

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    @Override
    public AnalyticsSummaryResponse getSummary(
            AnalyticsDateRange range, UUID eventId, UUID eventSessionId) {
        log.info("Admin analytics summary requested. from={}, to={}, eventId={}, eventSessionId={}",
                range.from(), range.to(), eventId, eventSessionId);

        OperationalTotals totals =
                adminQueries.sumOperationalTotals(range.from(), range.to(), eventId, eventSessionId);
        List<MoneyMetricResponse> revenue = adminQueries
                .sumRevenueByCurrency(range.from(), range.to(), eventId, eventSessionId)
                .stream()
                .map(cell -> MoneyMetricResponse.of(cell.currency(), cell.grossMinor(), cell.refundedMinor()))
                .toList();

        Instant fromInstant = range.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = range.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        CohortCounts conversion =
                projectionQueries.conversionCohort(fromInstant, toInstant, eventId, eventSessionId);
        CohortCounts expiration =
                projectionQueries.expirationCohort(fromInstant, toInstant, eventId, eventSessionId);
        long refundDenominator =
                adminQueries.refundDenominator(fromInstant, toInstant, eventId, eventSessionId);
        long refundNumerator =
                adminQueries.refundNumerator(fromInstant, toInstant, eventId, eventSessionId);

        return new AnalyticsSummaryResponse(
                range.from(),
                range.to(),
                new AnalyticsSummaryResponse.Filters(eventId, eventSessionId),
                new AnalyticsSummaryResponse.Reservations(
                        totals.reservationsCreated(), totals.reservationsConfirmed(),
                        totals.reservationsExpired(), totals.refundsCompleted()),
                new AnalyticsSummaryResponse.Tickets(
                        totals.ticketsIssued(), totals.ticketsRevoked(), totals.ticketsScanned()),
                new AnalyticsSummaryResponse.Payments(
                        totals.paymentsSucceeded(), totals.paymentsWithFailure(),
                        totals.refundsCompleted(), revenue),
                new AnalyticsSummaryResponse.Rates(
                        RateMetricResponse.of(conversion.numerator(), conversion.denominator()),
                        RateMetricResponse.of(expiration.numerator(), expiration.denominator()),
                        RateMetricResponse.of(refundNumerator, refundDenominator)),
                freshness());
    }

    // ------------------------------------------------------------------
    // Time series
    // ------------------------------------------------------------------

    @Override
    public AnalyticsTimeSeriesResponse getTimeSeries(
            AnalyticsDateRange range, UUID eventId, UUID eventSessionId,
            AnalyticsTimeseriesMetric metric) {
        if (metric == null) {
            throw new ValidationException(
                    "Analytics timeseries metric is required", ErrorCode.INVALID_ANALYTICS_SORT);
        }
        log.info("Admin analytics timeseries requested. metric={}, from={}, to={}, eventId={}, eventSessionId={}",
                metric, range.from(), range.to(), eventId, eventSessionId);

        List<AnalyticsTimeSeriesResponse.Series> series;
        if (metric.isMoney()) {
            series = moneySeries(range, eventId, eventSessionId, metric);
        } else {
            series = List.of(countSeries(range, eventId, eventSessionId, metric));
        }
        return new AnalyticsTimeSeriesResponse(metric.name(), range.from(), range.to(), series);
    }

    private AnalyticsTimeSeriesResponse.Series countSeries(
            AnalyticsDateRange range, UUID eventId, UUID eventSessionId,
            AnalyticsTimeseriesMetric metric) {
        ToLongFunction<DailyOperationalMetric> extractor = switch (metric) {
            case TICKETS_ISSUED -> DailyOperationalMetric::getTicketsIssued;
            case TICKETS_SCANNED -> DailyOperationalMetric::getTicketsScanned;
            case RESERVATIONS_CREATED -> DailyOperationalMetric::getReservationsCreated;
            case PAYMENTS_SUCCEEDED -> DailyOperationalMetric::getPaymentsSucceeded;
            default -> throw new ValidationException(
                    "Unsupported count timeseries metric: " + metric, ErrorCode.INVALID_ANALYTICS_SORT);
        };
        Map<LocalDate, Long> byDate = new LinkedHashMap<>();
        for (DailyOperationalMetric row
                : adminQueries.findOperationalRows(range.from(), range.to(), eventId, eventSessionId)) {
            byDate.merge(row.getMetricDate(), extractor.applyAsLong(row), Long::sum);
        }
        return AnalyticsTimeSeriesResponse.Series.countSeries(fillGaps(range, byDate));
    }

    private List<AnalyticsTimeSeriesResponse.Series> moneySeries(
            AnalyticsDateRange range, UUID eventId, UUID eventSessionId,
            AnalyticsTimeseriesMetric metric) {
        Map<String, Map<LocalDate, Long>> byCurrency = new TreeMap<>();
        for (DailyRevenueMetric row
                : adminQueries.findRevenueRows(range.from(), range.to(), eventId, eventSessionId)) {
            long value = switch (metric) {
                case GROSS_REVENUE -> row.getGrossRevenueMinor();
                case NET_REVENUE -> row.getGrossRevenueMinor() - row.getRefundedRevenueMinor();
                default -> throw new ValidationException(
                        "Unsupported money timeseries metric: " + metric, ErrorCode.INVALID_ANALYTICS_SORT);
            };
            byCurrency.computeIfAbsent(row.getCurrency(), c -> new LinkedHashMap<>())
                    .merge(row.getMetricDate(), value, Long::sum);
        }
        List<AnalyticsTimeSeriesResponse.Series> series = new ArrayList<>();
        for (Map.Entry<String, Map<LocalDate, Long>> entry : byCurrency.entrySet()) {
            series.add(AnalyticsTimeSeriesResponse.Series.moneySeries(
                    entry.getKey(), fillGaps(range, entry.getValue())));
        }
        return series;
    }

    private static List<AnalyticsDailyPointResponse> fillGaps(
            AnalyticsDateRange range, Map<LocalDate, Long> byDate) {
        List<AnalyticsDailyPointResponse> points = new ArrayList<>();
        for (LocalDate date = range.from(); !date.isAfter(range.to()); date = date.plusDays(1)) {
            points.add(new AnalyticsDailyPointResponse(date, byDate.getOrDefault(date, 0L)));
        }
        return points;
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    @Override
    public PagedResult<EventSessionAnalyticsResponse> getSessions(
            AnalyticsDateRange range, UUID eventId,
            int page, int size, AnalyticsSessionSort sort, boolean descending, String currency) {
        if (page < 0) {
            throw new ValidationException(
                    "Analytics page index must be zero or greater", ErrorCode.INVALID_ANALYTICS_LIMIT);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException(
                    "Analytics page size must be between 1 and " + MAX_PAGE_SIZE,
                    ErrorCode.INVALID_ANALYTICS_LIMIT);
        }
        AnalyticsSessionSort effectiveSort = sort != null ? sort : AnalyticsSessionSort.STARTS_AT;
        String effectiveCurrency = null;
        if (effectiveSort.isMonetary()) {
            effectiveCurrency = requireCurrency(currency,
                    "Sorting by grossRevenue requires currency=<3-letter code>");
        }
        long offset = (long) page * (long) size;
        if (offset > Integer.MAX_VALUE) {
            throw new ValidationException(
                    "Analytics page is beyond the available range", ErrorCode.INVALID_ANALYTICS_LIMIT);
        }
        log.info("Admin analytics sessions requested. from={}, to={}, eventId={}, page={}, size={}, sort={}, currency={}",
                range.from(), range.to(), eventId, page, size, effectiveSort.param(), effectiveCurrency);

        long total = adminQueries.countDistinctSessions(range.from(), range.to(), eventId);
        List<UUID> ids = adminQueries.pageSessionIds(range.from(), range.to(), eventId,
                effectiveSort, descending, effectiveCurrency, (int) offset, size);
        List<EventSessionAnalyticsResponse> content = enrichSessions(range, ids);
        return PagedResult.of(content, page, size, total);
    }

    /**
     * Page operational session rows first, then enrich with one batched revenue query, one
     * batched operational-sum query, and one batched fact load. No N+1, and {@code totalElements}
     * is never corrupted by revenue-row multiplication.
     */
    private List<EventSessionAnalyticsResponse> enrichSessions(
            AnalyticsDateRange range, List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, OperationalTotals> operational = new LinkedHashMap<>();
        Map<UUID, UUID> eventBySession = new LinkedHashMap<>();
        for (var row : adminQueries.sumOperationalForSessions(range.from(), range.to(), sessionIds)) {
            operational.put(row.eventSessionId(), row.totals());
            eventBySession.put(row.eventSessionId(), row.eventId());
        }
        Map<UUID, List<MoneyMetricResponse>> revenue = new LinkedHashMap<>();
        for (var row : adminQueries.sumRevenueForSessions(range.from(), range.to(), sessionIds)) {
            revenue.computeIfAbsent(row.eventSessionId(), s -> new ArrayList<>())
                    .add(MoneyMetricResponse.of(row.currency(), row.grossMinor(), row.refundedMinor()));
        }
        Map<UUID, AnalyticsSessionFact> facts = new LinkedHashMap<>();
        sessionFactRepository.findAllById(sessionIds).forEach(fact -> facts.put(fact.getEventSessionId(), fact));

        List<EventSessionAnalyticsResponse> content = new ArrayList<>(sessionIds.size());
        for (UUID sessionId : sessionIds) {
            OperationalTotals totals = operational.getOrDefault(sessionId, OperationalTotals.zero());
            AnalyticsSessionFact fact = facts.get(sessionId);
            UUID eventId = fact != null ? fact.getEventId() : eventBySession.get(sessionId);
            content.add(new EventSessionAnalyticsResponse(
                    eventId, sessionId,
                    fact != null ? fact.getEventTitle() : null,
                    fact != null ? fact.getSessionLabel() : null,
                    fact != null ? fact.getStartsAt() : null,
                    fact != null ? fact.getStatus() : null,
                    fact != null ? fact.getCapacitySnapshot() : null,
                    totals.reservationsCreated(), totals.reservationsConfirmed(), totals.reservationsExpired(),
                    totals.paymentsSucceeded(), totals.paymentsWithFailure(), totals.refundsCompleted(),
                    totals.ticketsIssued(), totals.ticketsRevoked(), totals.ticketsScanned(),
                    revenue.getOrDefault(sessionId, List.of()),
                    occupancyRatio(fact != null ? fact.getCapacitySnapshot() : null,
                            totals.ticketsIssued() - totals.ticketsRevoked()),
                    attendanceRatio(totals.ticketsIssued() - totals.ticketsRevoked(), totals.ticketsScanned()),
                    fact != null ? fact.getLastSourceEventAt() : null));
        }
        return content;
    }

    // ------------------------------------------------------------------
    // Session detail
    // ------------------------------------------------------------------

    @Override
    public EventSessionAnalyticsResponse getSessionDetail(UUID eventSessionId) {
        log.info("Admin analytics session detail requested. eventSessionId={}", eventSessionId);
        AnalyticsSessionFact fact = sessionFactRepository.findById(eventSessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Analytics session", eventSessionId));
        EventSessionMetric metric = sessionMetricRepository.findById(eventSessionId).orElse(null);
        OperationalTotals totals = metric != null
                ? new OperationalTotals(
                        metric.getReservationsCreated(), metric.getReservationsConfirmed(),
                        metric.getReservationsExpired(), metric.getPaymentsSucceeded(),
                        metric.getPaymentsWithFailure(), metric.getRefundsCompleted(),
                        metric.getTicketsIssued(), metric.getTicketsRevoked(), metric.getTicketsScanned())
                : OperationalTotals.zero();
        List<MoneyMetricResponse> revenue = sessionRevenueRepository.findByEventSessionId(eventSessionId)
                .stream()
                .sorted((left, right) -> left.getCurrency().compareTo(right.getCurrency()))
                .map(row -> MoneyMetricResponse.of(
                        row.getCurrency(), row.getGrossRevenueMinor(), row.getRefundedRevenueMinor()))
                .toList();

        AttendanceCounts attendance = projectionQueries.attendance(eventSessionId);
        OccupancyInputs occupancy = projectionQueries.occupancy(eventSessionId);

        return new EventSessionAnalyticsResponse(
                fact.getEventId(), fact.getEventSessionId(),
                fact.getEventTitle(), fact.getSessionLabel(), fact.getStartsAt(), fact.getStatus(),
                fact.getCapacitySnapshot(),
                totals.reservationsCreated(), totals.reservationsConfirmed(), totals.reservationsExpired(),
                totals.paymentsSucceeded(), totals.paymentsWithFailure(), totals.refundsCompleted(),
                totals.ticketsIssued(), totals.ticketsRevoked(), totals.ticketsScanned(),
                revenue,
                exactOccupancyRatio(occupancy),
                exactAttendanceRatio(attendance),
                latestOf(metric != null ? metric.getLastProjectedEventAt() : null,
                        fact.getLastSourceEventAt()));
    }

    // ------------------------------------------------------------------
    // Top rankings
    // ------------------------------------------------------------------

    @Override
    public List<TopAnalyticsItemResponse> getTop(
            AnalyticsDateRange range, UUID eventId,
            AnalyticsTopMetric metric, int limit, String currency) {
        if (metric == null) {
            throw new ValidationException(
                    "Analytics top metric is required", ErrorCode.INVALID_ANALYTICS_SORT);
        }
        if (limit < 1 || limit > MAX_TOP_LIMIT) {
            throw new ValidationException(
                    "Analytics top limit must be between 1 and " + MAX_TOP_LIMIT,
                    ErrorCode.INVALID_ANALYTICS_LIMIT);
        }
        String effectiveCurrency = null;
        if (metric.isMoney()) {
            effectiveCurrency = requireCurrency(currency,
                    "Ranking by NET_REVENUE requires currency=<3-letter code>");
        }
        log.info("Admin analytics top requested. metric={}, limit={}, from={}, to={}, eventId={}, currency={}",
                metric, limit, range.from(), range.to(), eventId, effectiveCurrency);

        List<UUID> ids = switch (metric) {
            // REV-002: NET_REVENUE ranks by net via its dedicated path; the sessions
            // listing sort=ranking grossRevenue orders by true gross.
            case NET_REVENUE -> adminQueries.pageSessionIdsByNetRevenue(range.from(), range.to(), eventId,
                    effectiveCurrency, true, 0, limit);
            case TICKETS_ISSUED -> adminQueries.pageSessionIds(range.from(), range.to(), eventId,
                    AnalyticsSessionSort.TICKETS_ISSUED, true, null, 0, limit);
            case TICKETS_SCANNED -> adminQueries.pageSessionIds(range.from(), range.to(), eventId,
                    AnalyticsSessionSort.TICKETS_SCANNED, true, null, 0, limit);
            case RESERVATIONS_CONFIRMED ->
                    adminQueries.rankSessionsByConfirmed(range.from(), range.to(), eventId, limit);
        };
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, OperationalTotals> operational = new LinkedHashMap<>();
        Map<UUID, UUID> eventBySession = new LinkedHashMap<>();
        for (var row : adminQueries.sumOperationalForSessions(range.from(), range.to(), ids)) {
            operational.put(row.eventSessionId(), row.totals());
            eventBySession.put(row.eventSessionId(), row.eventId());
        }
        Map<UUID, Long> netBySession = new LinkedHashMap<>();
        if (metric.isMoney()) {
            for (var row : adminQueries.sumRevenueForSessions(range.from(), range.to(), ids)) {
                if (effectiveCurrency.equals(row.currency())) {
                    netBySession.merge(row.eventSessionId(),
                            row.grossMinor() - row.refundedMinor(), Long::sum);
                }
            }
        }
        Map<UUID, AnalyticsSessionFact> facts = new LinkedHashMap<>();
        sessionFactRepository.findAllById(ids).forEach(fact -> facts.put(fact.getEventSessionId(), fact));

        List<TopAnalyticsItemResponse> items = new ArrayList<>(ids.size());
        for (UUID sessionId : ids) {
            OperationalTotals totals = operational.getOrDefault(sessionId, OperationalTotals.zero());
            AnalyticsSessionFact fact = facts.get(sessionId);
            long value = switch (metric) {
                case NET_REVENUE -> netBySession.getOrDefault(sessionId, 0L);
                case TICKETS_ISSUED -> totals.ticketsIssued();
                case TICKETS_SCANNED -> totals.ticketsScanned();
                case RESERVATIONS_CONFIRMED -> totals.reservationsConfirmed();
            };
            items.add(new TopAnalyticsItemResponse(
                    fact != null ? fact.getEventId() : eventBySession.get(sessionId),
                    sessionId,
                    fact != null ? fact.getEventTitle() : null,
                    fact != null ? fact.getSessionLabel() : null,
                    fact != null ? fact.getStartsAt() : null,
                    metric.name(), value, effectiveCurrency));
        }
        return items;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ProjectionFreshnessResponse freshness() {
        return ProjectionFreshnessResponse.of(
                Instant.now(analyticsClock),
                adminQueries.maxFactSourceTime(),
                adminQueries.maxProcessedAt());
    }

    private static String requireCurrency(String currency, String message) {
        if (currency == null || currency.isBlank()) {
            throw new ValidationException(message, ErrorCode.INVALID_ANALYTICS_SORT);
        }
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new ValidationException(
                    "Analytics currency must be a 3-letter ISO code: " + currency,
                    ErrorCode.INVALID_ANALYTICS_SORT);
        }
        return normalized;
    }

    /** Row occupancy derived from listed aggregates; null when no trusted capacity exists. */
    static BigDecimal occupancyRatio(Integer capacitySnapshot, long activeIssued) {
        if (capacitySnapshot == null || capacitySnapshot <= 0) {
            return null;
        }
        return ratio(activeIssued, capacitySnapshot);
    }

    /** Row attendance derived from listed aggregates; null when no eligible tickets exist. */
    static BigDecimal attendanceRatio(long eligibleIssued, long scanned) {
        if (eligibleIssued <= 0) {
            return null;
        }
        return ratio(scanned, eligibleIssued);
    }

    private static BigDecimal exactOccupancyRatio(OccupancyInputs inputs) {
        if (inputs.capacitySnapshot() == null || inputs.capacitySnapshot() <= 0) {
            return null;
        }
        return ratio(inputs.activeIssued(), inputs.capacitySnapshot());
    }

    private static BigDecimal exactAttendanceRatio(AttendanceCounts counts) {
        if (counts.eligibleIssued() <= 0) {
            return null;
        }
        return ratio(counts.scanned(), counts.eligibleIssued());
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static Instant latestOf(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }
}
