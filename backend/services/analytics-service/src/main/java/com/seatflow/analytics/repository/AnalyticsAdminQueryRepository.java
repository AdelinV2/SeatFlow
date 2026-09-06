package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.enums.AnalyticsSessionSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Bounded admin-query repository for TASK-P14-004.
 *
 * <p>Every query is bounded by the validated UTC date range and/or page/limit, uses typed
 * parameter binding (never concatenated SQL), and keeps the P14-003 aggregate-grain boundary:
 * operational counts come from currency-neutral {@code daily_operational_metrics} (one row per
 * session/date, never multiplied by currency rows); money comes from currency-keyed
 * {@code daily_revenue_metrics} grouped by currency. Java aggregates; the database sums.
 *
 * <p>Dynamic ordering is built only from the allowlisted {@link AnalyticsSessionSort} via a
 * code-controlled switch — user input never reaches the query string.
 */
@Repository
public class AnalyticsAdminQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** Currency-neutral operational sums. */
    public record OperationalTotals(
            long reservationsCreated, long reservationsConfirmed, long reservationsExpired,
            long paymentsSucceeded, long paymentsWithFailure, long refundsCompleted,
            long ticketsIssued, long ticketsRevoked, long ticketsScanned) {

        public static OperationalTotals zero() {
            return new OperationalTotals(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** One currency cell of financial sums. */
    public record RevenueTotal(
            String currency, long paymentsSucceeded, long refundsCompleted,
            long grossMinor, long refundedMinor) {
    }

    /** One session's in-range operational sums with its owning event. */
    public record SessionOperationalRow(
            UUID eventSessionId, UUID eventId, OperationalTotals totals) {
    }

    /** One session's in-range revenue cell. */
    public record SessionRevenueRow(
            UUID eventSessionId, String currency, long paymentsSucceeded, long refundsCompleted,
            long grossMinor, long refundedMinor) {
    }

    // ------------------------------------------------------------------
    // Summary aggregates
    // ------------------------------------------------------------------

    public OperationalTotals sumOperationalTotals(
            LocalDate from, LocalDate to, UUID eventId, UUID eventSessionId) {
        Object[] row = entityManager.createQuery("""
                SELECT COALESCE(SUM(d.reservationsCreated), 0),
                       COALESCE(SUM(d.reservationsConfirmed), 0),
                       COALESCE(SUM(d.reservationsExpired), 0),
                       COALESCE(SUM(d.paymentsSucceeded), 0),
                       COALESCE(SUM(d.paymentsWithFailure), 0),
                       COALESCE(SUM(d.refundsCompleted), 0),
                       COALESCE(SUM(d.ticketsIssued), 0),
                       COALESCE(SUM(d.ticketsRevoked), 0),
                       COALESCE(SUM(d.ticketsScanned), 0)
                FROM DailyOperationalMetric d
                WHERE d.metricDate BETWEEN :from AND :to
                  AND (:e IS NULL OR d.eventId = :e)
                  AND (:s IS NULL OR d.eventSessionId = :s)
                """, Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("e", eventId)
                .setParameter("s", eventSessionId)
                .getSingleResult();
        return new OperationalTotals(toLong(row[0]), toLong(row[1]), toLong(row[2]),
                toLong(row[3]), toLong(row[4]), toLong(row[5]),
                toLong(row[6]), toLong(row[7]), toLong(row[8]));
    }

    public List<RevenueTotal> sumRevenueByCurrency(
            LocalDate from, LocalDate to, UUID eventId, UUID eventSessionId) {
        return entityManager.createQuery("""
                SELECT r.currency,
                       COALESCE(SUM(r.paymentsSucceeded), 0),
                       COALESCE(SUM(r.refundsCompleted), 0),
                       COALESCE(SUM(r.grossRevenueMinor), 0),
                       COALESCE(SUM(r.refundedRevenueMinor), 0)
                FROM DailyRevenueMetric r
                WHERE r.metricDate BETWEEN :from AND :to
                  AND (:e IS NULL OR r.eventId = :e)
                  AND (:s IS NULL OR r.eventSessionId = :s)
                GROUP BY r.currency
                ORDER BY r.currency ASC
                """, Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("e", eventId)
                .setParameter("s", eventSessionId)
                .getResultList()
                .stream()
                .map(row -> new RevenueTotal((String) row[0], toLong(row[1]), toLong(row[2]),
                        toLong(row[3]), toLong(row[4])))
                .toList();
    }

    // ------------------------------------------------------------------
    // Refund cohort with event scope (same P14-003 definition as
    // AnalyticsProjectionQueryRepository.refundCohort: successful-payment cohort by completion
    // date, numerator = completed refund known; only the scope widens to eventId).
    //
    // REV-003: AnalyticsPaymentFact carries no eventId, so event scope resolves through the
    // reservation join (reservationId is non-null on payment facts, and a completed payment
    // implies its reservation fact in full-refund scope) — NOT through session facts, which
    // would silently drop payments whose session lifecycle event has not projected yet.
    // Residual: a payment whose reservation fact is itself still unprojected (cross-topic lag)
    // is transiently absent from the event-scoped cohort and converges once it arrives.
    // ------------------------------------------------------------------

    public long refundDenominator(
            Instant from, Instant to, UUID eventId, UUID eventSessionId) {
        return refundCohortCount(from, to, eventId, eventSessionId, false);
    }

    public long refundNumerator(
            Instant from, Instant to, UUID eventId, UUID eventSessionId) {
        return refundCohortCount(from, to, eventId, eventSessionId, true);
    }

    private long refundCohortCount(
            Instant from, Instant to, UUID eventId, UUID eventSessionId, boolean refundedOnly) {
        String jpql = """
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.completedAt >= :from AND p.completedAt < :to
                  AND (:s IS NULL OR p.eventSessionId = :s)
                  AND (:e IS NULL OR p.reservationId IN
                        (SELECT r.reservationId FROM AnalyticsReservationFact r WHERE r.eventId = :e))
                """ + (refundedOnly ? "AND p.refundedAt IS NOT NULL" : "");
        return entityManager.createQuery(jpql, Long.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("s", eventSessionId)
                .setParameter("e", eventId)
                .getSingleResult();
    }

    // ------------------------------------------------------------------
    // Time-series rows (one bounded query per request; gap fill happens in memory)
    // ------------------------------------------------------------------

    public List<DailyOperationalMetric> findOperationalRows(
            LocalDate from, LocalDate to, UUID eventId, UUID eventSessionId) {
        return entityManager.createQuery("""
                SELECT d FROM DailyOperationalMetric d
                WHERE d.metricDate BETWEEN :from AND :to
                  AND (:e IS NULL OR d.eventId = :e)
                  AND (:s IS NULL OR d.eventSessionId = :s)
                ORDER BY d.metricDate ASC
                """, DailyOperationalMetric.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("e", eventId)
                .setParameter("s", eventSessionId)
                .getResultList();
    }

    public List<DailyRevenueMetric> findRevenueRows(
            LocalDate from, LocalDate to, UUID eventId, UUID eventSessionId) {
        return entityManager.createQuery("""
                SELECT r FROM DailyRevenueMetric r
                WHERE r.metricDate BETWEEN :from AND :to
                  AND (:e IS NULL OR r.eventId = :e)
                  AND (:s IS NULL OR r.eventSessionId = :s)
                ORDER BY r.metricDate ASC, r.currency ASC
                """, DailyRevenueMetric.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("e", eventId)
                .setParameter("s", eventSessionId)
                .getResultList();
    }

    // ------------------------------------------------------------------
    // Session paging: distinct in-range sessions first, then batched enrichment
    // ------------------------------------------------------------------

    public long countDistinctSessions(LocalDate from, LocalDate to, UUID eventId) {
        return entityManager.createQuery("""
                SELECT COUNT(DISTINCT d.eventSessionId) FROM DailyOperationalMetric d
                WHERE d.metricDate BETWEEN :from AND :to
                  AND (:e IS NULL OR d.eventId = :e)
                """, Long.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("e", eventId)
                .getSingleResult();
    }

    /**
     * Page distinct in-range session IDs in a deterministic order.
     *
     * <p>REV-002: monetary listing order is true in-range <em>gross</em> revenue
     * ({@code SUM(grossRevenueMinor)}) for the requested currency via a correlated scalar
     * subquery, so sessions without revenue in that currency sort as zero instead of
     * vanishing from the page (which would corrupt {@code totalElements}). Net-revenue
     * ranking is a separate path: {@link #pageSessionIdsByNetRevenue}.
     */
    public List<UUID> pageSessionIds(
            LocalDate from, LocalDate to, UUID eventId,
            AnalyticsSessionSort sort, boolean descending, String currency,
            int offset, int limit) {
        String direction = descending ? "DESC" : "ASC";
        String orderBy = switch (sort) {
            case STARTS_AT -> """
                    (SELECT f.startsAt FROM AnalyticsSessionFact f
                     WHERE f.eventSessionId = d.eventSessionId) %s NULLS LAST, d.eventSessionId ASC
                    """.formatted(direction);
            case GROSS_REVENUE -> """
                    (SELECT COALESCE(SUM(r.grossRevenueMinor), 0)
                     FROM DailyRevenueMetric r
                     WHERE r.eventSessionId = d.eventSessionId AND r.currency = :ccy
                       AND r.metricDate BETWEEN :from AND :to) %s, d.eventSessionId ASC
                    """.formatted(direction);
            case TICKETS_ISSUED -> "SUM(d.ticketsIssued) %s, d.eventSessionId ASC".formatted(direction);
            case TICKETS_SCANNED -> "SUM(d.ticketsScanned) %s, d.eventSessionId ASC".formatted(direction);
            case RESERVATIONS_CREATED ->
                    "SUM(d.reservationsCreated) %s, d.eventSessionId ASC".formatted(direction);
        };
        String currencyOrNull = sort == AnalyticsSessionSort.GROSS_REVENUE ? currency : null;
        return executeRankedSessions(from, to, eventId, orderBy, currencyOrNull, offset, limit);
    }

    /**
     * Rank distinct in-range session IDs by in-range <em>net</em> revenue
     * ({@code SUM(gross - refunded)}) for exactly one currency.
     *
     * <p>REV-002: dedicated path for the top-{@code NET_REVENUE} ranking. It intentionally
     * duplicates the correlated-subquery shape of the gross listing sort instead of sharing
     * a flag, so a future change to listing order cannot silently change ranking semantics.
     */
    public List<UUID> pageSessionIdsByNetRevenue(
            LocalDate from, LocalDate to, UUID eventId, String currency,
            boolean descending, int offset, int limit) {
        String direction = descending ? "DESC" : "ASC";
        String orderBy = """
                (SELECT COALESCE(SUM(r.grossRevenueMinor - r.refundedRevenueMinor), 0)
                 FROM DailyRevenueMetric r
                 WHERE r.eventSessionId = d.eventSessionId AND r.currency = :ccy
                   AND r.metricDate BETWEEN :from AND :to) %s, d.eventSessionId ASC
                """.formatted(direction);
        return executeRankedSessions(from, to, eventId, orderBy, currency, offset, limit);
    }

    private List<UUID> executeRankedSessions(
            LocalDate from, LocalDate to, UUID eventId,
            String orderBy, String currencyOrNull, int offset, int limit) {
        String jpql = """
                SELECT d.eventSessionId FROM DailyOperationalMetric d
                WHERE d.metricDate BETWEEN :from AND :to
                  AND (:e IS NULL OR d.eventId = :e)
                GROUP BY d.eventSessionId
                ORDER BY %s
                """.formatted(orderBy);
        var query = entityManager.createQuery(jpql, UUID.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("e", eventId);
        if (currencyOrNull != null) {
            query.setParameter("ccy", currencyOrNull);
        }
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    /**
     * Rank in-range sessions by confirmed reservations for the top endpoint.
     *
     * <p>Kept separate from {@link #pageSessionIds} so the public session-sort allowlist
     * ({@code startsAt, grossRevenue, ticketsIssued, ticketsScanned, reservationsCreated})
     * is never widened: this ordering is only reachable via the top metric
     * {@code RESERVATIONS_CONFIRMED}.
     */
    public List<UUID> rankSessionsByConfirmed(
            LocalDate from, LocalDate to, UUID eventId, int limit) {
        return entityManager.createQuery("""
                SELECT d.eventSessionId FROM DailyOperationalMetric d
                WHERE d.metricDate BETWEEN :from AND :to
                  AND (:e IS NULL OR d.eventId = :e)
                GROUP BY d.eventSessionId
                ORDER BY SUM(d.reservationsConfirmed) DESC, d.eventSessionId ASC
                """, UUID.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("e", eventId)
                .setFirstResult(0)
                .setMaxResults(limit)
                .getResultList();
    }

    /** In-range operational sums for exactly the page's session IDs (one batched query). */    public List<SessionOperationalRow> sumOperationalForSessions(
            LocalDate from, LocalDate to, Collection<UUID> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                SELECT d.eventSessionId, MAX(d.eventId),
                       COALESCE(SUM(d.reservationsCreated), 0),
                       COALESCE(SUM(d.reservationsConfirmed), 0),
                       COALESCE(SUM(d.reservationsExpired), 0),
                       COALESCE(SUM(d.paymentsSucceeded), 0),
                       COALESCE(SUM(d.paymentsWithFailure), 0),
                       COALESCE(SUM(d.refundsCompleted), 0),
                       COALESCE(SUM(d.ticketsIssued), 0),
                       COALESCE(SUM(d.ticketsRevoked), 0),
                       COALESCE(SUM(d.ticketsScanned), 0)
                FROM DailyOperationalMetric d
                WHERE d.metricDate BETWEEN :from AND :to
                  AND d.eventSessionId IN :ids
                GROUP BY d.eventSessionId
                """, Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("ids", List.copyOf(sessionIds))
                .getResultList()
                .stream()
                .map(row -> new SessionOperationalRow((UUID) row[0], (UUID) row[1],
                        new OperationalTotals(toLong(row[2]), toLong(row[3]), toLong(row[4]),
                                toLong(row[5]), toLong(row[6]), toLong(row[7]),
                                toLong(row[8]), toLong(row[9]), toLong(row[10]))))
                .toList();
    }

    /** In-range revenue cells for exactly the page's session IDs (one batched query). */
    public List<SessionRevenueRow> sumRevenueForSessions(
            LocalDate from, LocalDate to, Collection<UUID> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                SELECT r.eventSessionId, r.currency,
                       COALESCE(SUM(r.paymentsSucceeded), 0),
                       COALESCE(SUM(r.refundsCompleted), 0),
                       COALESCE(SUM(r.grossRevenueMinor), 0),
                       COALESCE(SUM(r.refundedRevenueMinor), 0)
                FROM DailyRevenueMetric r
                WHERE r.metricDate BETWEEN :from AND :to
                  AND r.eventSessionId IN :ids
                GROUP BY r.eventSessionId, r.currency
                ORDER BY r.eventSessionId ASC, r.currency ASC
                """, Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("ids", List.copyOf(sessionIds))
                .getResultList()
                .stream()
                .map(row -> new SessionRevenueRow((UUID) row[0], (String) row[1],
                        toLong(row[2]), toLong(row[3]), toLong(row[4]), toLong(row[5])))
                .toList();
    }

    // ------------------------------------------------------------------
    // Freshness anchors (single-row MAX queries; null before the first event)
    // ------------------------------------------------------------------

    public Instant maxProcessedAt() {
        return entityManager.createQuery(
                "SELECT MAX(p.processedAt) FROM ProcessedEvent p", Instant.class)
                .getSingleResult();
    }

    /** Max source-event time over every analytics-owned fact (delivery-order independent). */
    public Instant maxFactSourceTime() {
        Instant best = null;
        best = later(best, maxInstant("SELECT MAX(r.lastSourceEventAt) FROM AnalyticsReservationFact r"));
        best = later(best, maxInstant("SELECT MAX(p.lastSourceEventAt) FROM AnalyticsPaymentFact p"));
        best = later(best, maxInstant("SELECT MAX(t.lastSourceEventAt) FROM AnalyticsTicketFact t"));
        best = later(best, maxInstant("SELECT MAX(f.lastSourceEventAt) FROM AnalyticsSessionFact f"));
        return best;
    }

    private Instant maxInstant(String jpql) {
        return entityManager.createQuery(jpql, Instant.class).getSingleResult();
    }

    private static Instant later(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.isAfter(current)) {
            return candidate;
        }
        return current;
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }
}
