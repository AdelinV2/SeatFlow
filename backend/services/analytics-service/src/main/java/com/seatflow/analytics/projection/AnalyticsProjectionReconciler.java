package com.seatflow.analytics.projection;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.entity.EventSessionMetric;
import com.seatflow.analytics.model.entity.EventSessionRevenueMetric;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.DailyRevenueMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deterministic aggregate reconciler (TASK-P14-003).
 *
 * <p>For every affected key, aggregates are <em>recomputed from analytics-owned facts</em> —
 * never maintained with {@code counter += delta} logic. Replaying the same retained event
 * history from an empty analytics DB therefore converges to the same rows regardless of
 * delivery order (within same-partition producer ordering).
 *
 * <p>Row lifecycle: operational session rows persist while their session fact exists;
 * currency-keyed financial rows and daily rows are deleted exactly when recomputation proves no
 * contributing facts remain. Operational and financial grains are recomputed independently so a
 * multi-currency session can never multiply operational counts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsProjectionReconciler {

    private final AnalyticsProjectionQueryRepository queries;
    private final AnalyticsSessionFactRepository sessionFacts;
    private final EventSessionMetricRepository sessionMetrics;
    private final EventSessionRevenueMetricRepository sessionRevenue;
    private final DailyOperationalMetricRepository dailyOperational;
    private final DailyRevenueMetricRepository dailyRevenue;

    /**
     * Recompute every affected aggregate key. Runs inside the P14-002 claim transaction, so a
     * failure rolls back facts, aggregates, and the idempotency claim together.
     *
     * @param sourceEventAt business-event time of the triggering event, folded into
     *                      {@code last_projected_event_at} as a max (order-independent).
     */
    @Transactional
    public void reconcile(ProjectionImpact impact, Instant sourceEventAt) {
        for (ProjectionImpact.SessionKey key : impact.sessions()) {
            recomputeSessionOperational(key, sourceEventAt);
            recomputeSessionRevenue(key.eventSessionId(), sourceEventAt);
        }
        for (ProjectionImpact.DailyKey key : impact.dailyOperational()) {
            recomputeDailyOperational(key);
        }
        for (ProjectionImpact.SessionCurrencyKey key : impact.sessionRevenue()) {
            recomputeOneSessionCurrency(key.eventSessionId(), key.currency(), sourceEventAt);
        }
        for (ProjectionImpact.DailyCurrencyKey key : impact.dailyRevenue()) {
            recomputeOneDailyCurrency(key, sourceEventAt);
        }
    }

    private void recomputeSessionOperational(
            ProjectionImpact.SessionKey key, Instant sourceEventAt) {
        AnalyticsSessionFact session = sessionFacts.findById(key.eventSessionId()).orElse(null);
        if (session == null) {
            log.debug("Skipping session recompute without session fact. session={}", key.eventSessionId());
            return;
        }
        var counts = queries.countSessionOperational(key.eventSessionId());
        EventSessionMetric row = sessionMetrics.findById(key.eventSessionId()).orElseGet(() ->
                EventSessionMetric.builder()
                        .eventSessionId(key.eventSessionId())
                        .eventId(session.getEventId())
                        .build());
        row.setEventId(session.getEventId());
        row.setCapacitySnapshot(session.getCapacitySnapshot());
        row.setReservationsCreated(counts.created());
        row.setReservationsConfirmed(counts.confirmed());
        row.setReservationsExpired(counts.expired());
        row.setPaymentsSucceeded(counts.paymentsSucceeded());
        row.setPaymentsWithFailure(counts.paymentsWithFailure());
        row.setRefundsCompleted(counts.refundsCompleted());
        row.setTicketsIssued(counts.ticketsIssued());
        row.setTicketsRevoked(counts.ticketsRevoked());
        row.setTicketsScanned(counts.ticketsScanned());
        row.setLastProjectedEventAt(
                maxInstant(row.getLastProjectedEventAt(), projectedAnchor(key.eventSessionId(), sourceEventAt)));
        row.setUpdatedAt(Instant.now());
        sessionMetrics.save(row);
    }

    private void recomputeSessionRevenue(UUID eventSessionId, Instant sourceEventAt) {
        AnalyticsSessionFact session = sessionFacts.findById(eventSessionId).orElse(null);
        if (session == null) {
            return;
        }
        Map<String, AnalyticsProjectionQueryRepository.SessionRevenueCell> fresh = queries
                .sumSessionRevenue(eventSessionId).stream()
                .collect(Collectors.toMap(
                        AnalyticsProjectionQueryRepository.SessionRevenueCell::currency,
                        Function.identity()));
        for (var cell : fresh.values()) {
            upsertSessionRevenue(session, cell, sourceEventAt);
        }
        for (EventSessionRevenueMetric existing : sessionRevenue.findByEventSessionId(eventSessionId)) {
            if (!fresh.containsKey(existing.getCurrency())) {
                sessionRevenue.delete(existing);
            }
        }
    }

    private void recomputeOneSessionCurrency(UUID eventSessionId, String currency, Instant sourceEventAt) {
        AnalyticsSessionFact session = sessionFacts.findById(eventSessionId).orElse(null);
        if (session == null) {
            return;
        }
        var cell = queries.sumSessionRevenue(eventSessionId).stream()
                .filter(c -> c.currency().equals(currency))
                .findFirst()
                .orElse(null);
        if (cell == null || (cell.paymentsSucceeded() == 0 && cell.grossMinor() == 0
                && cell.refundsCompleted() == 0 && cell.refundedMinor() == 0)) {
            sessionRevenue.findById(new EventSessionRevenueMetric.Key(eventSessionId, currency))
                    .ifPresent(sessionRevenue::delete);
            return;
        }
        upsertSessionRevenue(session, cell, sourceEventAt);
    }

    private void upsertSessionRevenue(
            AnalyticsSessionFact session,
            AnalyticsProjectionQueryRepository.SessionRevenueCell cell,
            Instant sourceEventAt) {
        EventSessionRevenueMetric row = sessionRevenue
                .findById(new EventSessionRevenueMetric.Key(session.getEventSessionId(), cell.currency()))
                .orElseGet(() -> EventSessionRevenueMetric.builder()
                        .eventSessionId(session.getEventSessionId())
                        .currency(cell.currency())
                        .eventId(session.getEventId())
                        .build());
        row.setEventId(session.getEventId());
        row.setPaymentsSucceeded(cell.paymentsSucceeded());
        row.setGrossRevenueMinor(cell.grossMinor());
        row.setRefundsCompleted(cell.refundsCompleted());
        row.setRefundedRevenueMinor(cell.refundedMinor());
        row.setLastProjectedEventAt(maxInstant(row.getLastProjectedEventAt(),
                projectedAnchor(session.getEventSessionId(), sourceEventAt)));
        row.setUpdatedAt(Instant.now());
        sessionRevenue.save(row);
    }

    private void recomputeDailyOperational(ProjectionImpact.DailyKey key) {
        Instant from = key.metricDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = key.metricDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        var counts = queries.countDailyOperational(from, to, key.eventId(), key.eventSessionId());
        var id = new DailyOperationalMetric.Key(key.metricDate(), key.eventId(), key.eventSessionId());
        if (counts.allZero()) {
            dailyOperational.findById(id).ifPresent(dailyOperational::delete);
            return;
        }
        DailyOperationalMetric row = dailyOperational.findById(id).orElseGet(() ->
                DailyOperationalMetric.builder()
                        .metricDate(key.metricDate())
                        .eventId(key.eventId())
                        .eventSessionId(key.eventSessionId())
                        .build());
        row.setReservationsCreated(counts.created());
        row.setReservationsConfirmed(counts.confirmed());
        row.setReservationsExpired(counts.expired());
        row.setPaymentsSucceeded(counts.paymentsSucceeded());
        row.setPaymentsWithFailure(counts.paymentsWithFailure());
        row.setRefundsCompleted(counts.refundsCompleted());
        row.setTicketsIssued(counts.ticketsIssued());
        row.setTicketsRevoked(counts.ticketsRevoked());
        row.setTicketsScanned(counts.ticketsScanned());
        row.setUpdatedAt(Instant.now());
        dailyOperational.save(row);
    }

    private void recomputeOneDailyCurrency(
            ProjectionImpact.DailyCurrencyKey key, Instant sourceEventAt) {
        Instant from = key.metricDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = key.metricDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        var cell = queries.sumDailyRevenue(from, to, key.eventSessionId()).stream()
                .filter(c -> c.currency().equals(key.currency()))
                .findFirst()
                .orElse(null);
        var id = new DailyRevenueMetric.Key(
                key.metricDate(), key.eventId(), key.eventSessionId(), key.currency());
        if (cell == null || (cell.paymentsSucceeded() == 0 && cell.grossMinor() == 0
                && cell.refundsCompleted() == 0 && cell.refundedMinor() == 0)) {
            dailyRevenue.findById(id).ifPresent(dailyRevenue::delete);
            return;
        }
        DailyRevenueMetric row = dailyRevenue.findById(id).orElseGet(() ->
                DailyRevenueMetric.builder()
                        .metricDate(key.metricDate())
                        .eventId(key.eventId())
                        .eventSessionId(key.eventSessionId())
                        .currency(key.currency())
                        .build());
        row.setPaymentsSucceeded(cell.paymentsSucceeded());
        row.setGrossRevenueMinor(cell.grossMinor());
        row.setRefundsCompleted(cell.refundsCompleted());
        row.setRefundedRevenueMinor(cell.refundedMinor());
        row.setUpdatedAt(Instant.now());
        dailyRevenue.save(row);
        log.debug("Daily revenue recomputed. date={} session={} currency={}",
                key.metricDate(), key.eventSessionId(), key.currency());
    }

    // ------------------------------------------------------------------
    // Cohort / rate inputs for P14-004 (no REST here)
    // ------------------------------------------------------------------

    /** Reservation-to-payment conversion inputs for a reservation-created cohort. */
    @Transactional(readOnly = true)
    public AnalyticsProjectionQueryRepository.CohortCounts conversionCohort(
            Instant from, Instant to, UUID eventId, UUID eventSessionId) {
        return queries.conversionCohort(from, to, eventId, eventSessionId);
    }

    /** Expiration inputs for a reservation-created cohort. */
    @Transactional(readOnly = true)
    public AnalyticsProjectionQueryRepository.CohortCounts expirationCohort(
            Instant from, Instant to, UUID eventId, UUID eventSessionId) {
        return queries.expirationCohort(from, to, eventId, eventSessionId);
    }

    /** Refund inputs for a successful-payment cohort by completion date. */
    @Transactional(readOnly = true)
    public AnalyticsProjectionQueryRepository.CohortCounts refundCohort(
            Instant from, Instant to, UUID eventSessionId) {
        return queries.refundCohort(from, to, eventSessionId);
    }

    /** Attendance inputs for one session. */
    @Transactional(readOnly = true)
    public AnalyticsProjectionQueryRepository.AttendanceCounts attendance(UUID eventSessionId) {
        return queries.attendance(eventSessionId);
    }

    /** Occupancy inputs for one session; capacity null/zero means unavailable. */
    @Transactional(readOnly = true)
    public AnalyticsProjectionQueryRepository.OccupancyInputs occupancy(UUID eventSessionId) {
        return queries.occupancy(eventSessionId);
    }

    /**
     * Conversion rate for a reservation-created cohort, or empty when the denominator is zero
     * (never NaN/Infinity). Cohort-based so purchases spanning dates cannot exceed 100%.
     */
    public static java.util.OptionalDouble conversionRate(
            AnalyticsProjectionQueryRepository.CohortCounts cohort) {
        if (cohort.denominator() == 0) {
            return java.util.OptionalDouble.empty();
        }
        return java.util.OptionalDouble.of((double) cohort.numerator() / cohort.denominator());
    }

    /**
     * Occupancy rate, or empty when no trusted capacity snapshot exists or it is zero.
     * Never synthesizes capacity from issued counts.
     */
    public static java.util.OptionalDouble occupancyRate(
            AnalyticsProjectionQueryRepository.OccupancyInputs inputs) {
        if (inputs.capacitySnapshot() == null || inputs.capacitySnapshot() <= 0) {
            return java.util.OptionalDouble.empty();
        }
        return java.util.OptionalDouble.of(
                (double) inputs.activeIssued() / inputs.capacitySnapshot());
    }

    /**
     * Attendance rate, or empty when no eligible (issued, non-revoked) tickets exist.
     */
    public static java.util.OptionalDouble attendanceRate(
            AnalyticsProjectionQueryRepository.AttendanceCounts counts) {
        if (counts.eligibleIssued() == 0) {
            return java.util.OptionalDouble.empty();
        }
        return java.util.OptionalDouble.of((double) counts.scanned() / counts.eligibleIssued());
    }

    static Instant maxInstant(Instant current, Instant candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate == null) {
            return current;
        }
        return candidate.isAfter(current) ? candidate : current;
    }

    /**
     * Order-independent freshness anchor: the max source-event time over converged facts,
     * falling back to the triggering event when no attributed facts exist yet.
     */
    private Instant projectedAnchor(UUID eventSessionId, Instant sourceEventAt) {
        Instant factsMax = queries.maxSessionFactTime(eventSessionId);
        return factsMax != null ? factsMax : sourceEventAt;
    }

    /** UTC calendar date for daily bucketing. */
    public static LocalDate utcDate(Instant value) {
        return value.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** Day-range query helper shared by tests and future read paths. */
    public List<AnalyticsProjectionQueryRepository.DailyRevenueCell> dailyRevenueCells(
            LocalDate date, UUID eventSessionId) {
        Instant from = date.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return queries.sumDailyRevenue(from, to, eventSessionId);
    }
}
