package com.seatflow.analytics.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic aggregate-recomputation and cohort/rate queries for TASK-P14-003.
 *
 * <p>All aggregates are derived from analytics-owned facts with database-side
 * {@code COUNT}/{@code SUM} queries: Java never loads unbounded fact collections to sum them.
 * Daily buckets use half-open UTC ranges over fact timestamps (never processing time).
 * Operational queries are currency-neutral; financial queries always group by currency so
 * operational counts can never be multiplied by a currency join.
 *
 * <p>Rate/cohort definitions (for P14-004 REST use; no REST is exposed here):
 * <ul>
 *   <li>conversion: reservation-created cohort in {@code [from, to)}; numerator = those with a
 *       correlated successful payment known at query time (reservation confirmed or refunded,
 *       which implies payment success in the P13 full-refund scope);</li>
 *   <li>expiration: same created cohort; numerator = canonical outcome EXPIRED;</li>
 *   <li>refund: successful-payment cohort by completion date; numerator = completed refund known;</li>
 *   <li>attendance: eligible issued tickets (issued, not revoked) vs. scanned among them;</li>
 *   <li>occupancy: active issued tickets vs. trusted capacity snapshot (null when unavailable).</li>
 * </ul>
 */
@Repository
public class AnalyticsProjectionQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /** Currency-neutral operational counts for one session. */
    public record SessionOperationalCounts(
            long created, long confirmed, long expired,
            long paymentsSucceeded, long paymentsWithFailure, long refundsCompleted,
            long ticketsIssued, long ticketsRevoked, long ticketsScanned) {
        public boolean allZero() {
            return created == 0 && confirmed == 0 && expired == 0
                    && paymentsSucceeded == 0 && paymentsWithFailure == 0 && refundsCompleted == 0
                    && ticketsIssued == 0 && ticketsRevoked == 0 && ticketsScanned == 0;
        }
    }

    /** One currency cell of session financial aggregates. */
    public record SessionRevenueCell(
            String currency, long paymentsSucceeded, long grossMinor,
            long refundsCompleted, long refundedMinor) {
    }

    /** One currency cell of daily financial aggregates. */
    public record DailyRevenueCell(
            String currency, long paymentsSucceeded, long grossMinor,
            long refundsCompleted, long refundedMinor) {
    }

    /** Cohort numerator/denominator pair; denominator zero means unavailable, never NaN. */
    public record CohortCounts(long denominator, long numerator) {
    }

    /** Attendance inputs for one session. */
    public record AttendanceCounts(long eligibleIssued, long scanned) {
    }

    /** Occupancy inputs for one session; capacity null/zero means unavailable. */
    public record OccupancyInputs(Integer capacitySnapshot, long activeIssued) {
    }

    // ------------------------------------------------------------------
    // Operational recomputation
    // ------------------------------------------------------------------

    public SessionOperationalCounts countSessionOperational(UUID eventSessionId) {
        long created = count(
                "SELECT COUNT(r) FROM AnalyticsReservationFact r WHERE r.eventSessionId = :s", eventSessionId);
        long confirmed = count("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.eventSessionId = :s AND (r.confirmedAt IS NOT NULL OR r.refundedAt IS NOT NULL)
                """, eventSessionId);
        long expired = count("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.eventSessionId = :s
                  AND r.refundedAt IS NULL AND r.confirmedAt IS NULL
                  AND r.cancelledAt IS NULL AND r.expiredAt IS NOT NULL
                """, eventSessionId);
        long payOk = count("""
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.completedAt IS NOT NULL
                """, eventSessionId);
        long payFail = count("""
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.failedAt IS NOT NULL
                """, eventSessionId);
        long refunds = count("""
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.completedAt IS NOT NULL AND p.refundedAt IS NOT NULL
                """, eventSessionId);
        long issued = count("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.issuedAt IS NOT NULL
                """, eventSessionId);
        long revoked = count("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.revokedAt IS NOT NULL
                """, eventSessionId);
        long scanned = count("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.firstScannedAt IS NOT NULL
                """, eventSessionId);
        return new SessionOperationalCounts(created, confirmed, expired, payOk, payFail, refunds,
                issued, revoked, scanned);
    }

    public SessionOperationalCounts countDailyOperational(
            Instant dayStartInclusive, Instant dayEndExclusive, UUID eventId, UUID eventSessionId) {
        long created = countInRange("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.eventId = :e AND r.eventSessionId = :s
                  AND r.createdAt >= :from AND r.createdAt < :to
                """, dayStartInclusive, dayEndExclusive, eventId, eventSessionId);
        long confirmed = countInRange("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.eventId = :e AND r.eventSessionId = :s
                  AND COALESCE(r.confirmedAt, r.refundedAt) >= :from
                  AND COALESCE(r.confirmedAt, r.refundedAt) < :to
                  AND (r.confirmedAt IS NOT NULL OR r.refundedAt IS NOT NULL)
                """, dayStartInclusive, dayEndExclusive, eventId, eventSessionId);
        long expired = countInRange("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.eventId = :e AND r.eventSessionId = :s
                  AND r.refundedAt IS NULL AND r.confirmedAt IS NULL
                  AND r.cancelledAt IS NULL AND r.expiredAt IS NOT NULL
                  AND r.expiredAt >= :from AND r.expiredAt < :to
                """, dayStartInclusive, dayEndExclusive, eventId, eventSessionId);
        long payOk = countInRange("""
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.completedAt >= :from AND p.completedAt < :to
                """, dayStartInclusive, dayEndExclusive, eventSessionId);
        long payFail = countInRange("""
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.failedAt >= :from AND p.failedAt < :to
                """, dayStartInclusive, dayEndExclusive, eventSessionId);
        long refunds = countInRange("""
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s
                  AND p.completedAt IS NOT NULL
                  AND p.refundedAt >= :from AND p.refundedAt < :to
                """, dayStartInclusive, dayEndExclusive, eventSessionId);
        long issued = countInRange("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.issuedAt >= :from AND t.issuedAt < :to
                """, dayStartInclusive, dayEndExclusive, eventSessionId);
        long revoked = countInRange("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.revokedAt >= :from AND t.revokedAt < :to
                """, dayStartInclusive, dayEndExclusive, eventSessionId);
        long scanned = countInRange("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.firstScannedAt >= :from AND t.firstScannedAt < :to
                """, dayStartInclusive, dayEndExclusive, eventSessionId);
        return new SessionOperationalCounts(created, confirmed, expired, payOk, payFail, refunds,
                issued, revoked, scanned);
    }

    // ------------------------------------------------------------------
    // Financial recomputation (currency-grouped; never mixed)
    // ------------------------------------------------------------------

    public List<SessionRevenueCell> sumSessionRevenue(UUID eventSessionId) {
        List<Object[]> gross = entityManager.createQuery("""
                SELECT p.currency, COUNT(p), COALESCE(SUM(p.completedAmountMinor), 0)
                FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.completedAt IS NOT NULL AND p.currency IS NOT NULL
                GROUP BY p.currency
                """, Object[].class)
                .setParameter("s", eventSessionId)
                .getResultList();
        List<Object[]> refunds = entityManager.createQuery("""
                SELECT p.currency, COUNT(p), COALESCE(SUM(p.refundedAmountMinor), 0)
                FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.completedAt IS NOT NULL AND p.refundedAt IS NOT NULL
                  AND p.currency IS NOT NULL
                GROUP BY p.currency
                """, Object[].class)
                .setParameter("s", eventSessionId)
                .getResultList();
        return mergeRevenueCells(gross, refunds);
    }

    public List<DailyRevenueCell> sumDailyRevenue(
            Instant dayStartInclusive, Instant dayEndExclusive, UUID eventSessionId) {
        List<Object[]> gross = entityManager.createQuery("""
                SELECT p.currency, COUNT(p), COALESCE(SUM(p.completedAmountMinor), 0)
                FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.currency IS NOT NULL
                  AND p.completedAt >= :from AND p.completedAt < :to
                GROUP BY p.currency
                """, Object[].class)
                .setParameter("s", eventSessionId)
                .setParameter("from", dayStartInclusive)
                .setParameter("to", dayEndExclusive)
                .getResultList();
        List<Object[]> refunds = entityManager.createQuery("""
                SELECT p.currency, COUNT(p), COALESCE(SUM(p.refundedAmountMinor), 0)
                FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s AND p.currency IS NOT NULL
                  AND p.completedAt IS NOT NULL
                  AND p.refundedAt >= :from AND p.refundedAt < :to
                GROUP BY p.currency
                """, Object[].class)
                .setParameter("s", eventSessionId)
                .setParameter("from", dayStartInclusive)
                .setParameter("to", dayEndExclusive)
                .getResultList();
        return mergeRevenueCells(gross, refunds).stream()
                .map(c -> new DailyRevenueCell(c.currency(), c.paymentsSucceeded(), c.grossMinor(),
                        c.refundsCompleted(), c.refundedMinor()))
                .toList();
    }

    private List<SessionRevenueCell> mergeRevenueCells(List<Object[]> gross, List<Object[]> refunds) {
        java.util.Map<String, long[]> byCurrency = new java.util.TreeMap<>();
        for (Object[] row : gross) {
            byCurrency.put((String) row[0],
                    new long[]{toLong(row[1]), toLong(row[2]), 0L, 0L});
        }
        for (Object[] row : refunds) {
            long[] cell = byCurrency.computeIfAbsent((String) row[0], k -> new long[4]);
            cell[2] = toLong(row[1]);
            cell[3] = toLong(row[2]);
        }
        return byCurrency.entrySet().stream()
                .map(e -> new SessionRevenueCell(e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[2], e.getValue()[3]))
                .toList();
    }

    // ------------------------------------------------------------------
    // Cohort / rate inputs (P14-004 use; no REST here)
    // ------------------------------------------------------------------

    /**
     * Reservation-created cohort in {@code [from, to)} with optional event/session scope.
     * Numerator = cohort reservations with a correlated successful payment known at query time
     * (confirmed or refunded outcome, which implies payment success in full-refund scope).
     */
    public CohortCounts conversionCohort(
            Instant from, Instant to, UUID eventId, UUID eventSessionId) {
        long denominator = cohortCount("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.createdAt >= :from AND r.createdAt < :to
                  AND (:e IS NULL OR r.eventId = :e)
                  AND (:s IS NULL OR r.eventSessionId = :s)
                """, from, to, eventId, eventSessionId);
        long numerator = cohortCount("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.createdAt >= :from AND r.createdAt < :to
                  AND (:e IS NULL OR r.eventId = :e)
                  AND (:s IS NULL OR r.eventSessionId = :s)
                  AND (r.confirmedAt IS NOT NULL OR r.refundedAt IS NOT NULL)
                """, from, to, eventId, eventSessionId);
        return new CohortCounts(denominator, numerator);
    }

    /** Same created cohort; numerator = canonical outcome EXPIRED. */
    public CohortCounts expirationCohort(
            Instant from, Instant to, UUID eventId, UUID eventSessionId) {
        long denominator = cohortCount("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.createdAt >= :from AND r.createdAt < :to
                  AND (:e IS NULL OR r.eventId = :e)
                  AND (:s IS NULL OR r.eventSessionId = :s)
                """, from, to, eventId, eventSessionId);
        long numerator = cohortCount("""
                SELECT COUNT(r) FROM AnalyticsReservationFact r
                WHERE r.createdAt >= :from AND r.createdAt < :to
                  AND (:e IS NULL OR r.eventId = :e)
                  AND (:s IS NULL OR r.eventSessionId = :s)
                  AND r.refundedAt IS NULL AND r.confirmedAt IS NULL
                  AND r.cancelledAt IS NULL AND r.expiredAt IS NOT NULL
                """, from, to, eventId, eventSessionId);
        return new CohortCounts(denominator, numerator);
    }

    /** Successful-payment cohort by completion date; numerator = completed refund known. */
    public CohortCounts refundCohort(
            Instant from, Instant to, UUID eventSessionId) {
        long denominator = entityManager.createQuery("""
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.completedAt >= :from AND p.completedAt < :to
                  AND (:s IS NULL OR p.eventSessionId = :s)
                """, Long.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("s", eventSessionId)
                .getSingleResult();
        long numerator = entityManager.createQuery("""
                SELECT COUNT(p) FROM AnalyticsPaymentFact p
                WHERE p.completedAt >= :from AND p.completedAt < :to
                  AND (:s IS NULL OR p.eventSessionId = :s)
                  AND p.refundedAt IS NOT NULL
                """, Long.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("s", eventSessionId)
                .getSingleResult();
        return new CohortCounts(denominator, numerator);
    }

    public AttendanceCounts attendance(UUID eventSessionId) {
        long eligible = entityManager.createQuery("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.issuedAt IS NOT NULL AND t.revokedAt IS NULL
                """, Long.class)
                .setParameter("s", eventSessionId)
                .getSingleResult();
        long scanned = entityManager.createQuery("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.issuedAt IS NOT NULL AND t.revokedAt IS NULL
                  AND t.firstScannedAt IS NOT NULL
                """, Long.class)
                .setParameter("s", eventSessionId)
                .getSingleResult();
        return new AttendanceCounts(eligible, scanned);
    }

    public OccupancyInputs occupancy(UUID eventSessionId) {
        Integer capacity = entityManager.createQuery("""
                SELECT f.capacitySnapshot FROM AnalyticsSessionFact f
                WHERE f.eventSessionId = :s AND f.capacitySnapshot IS NOT NULL
                """, Integer.class)
                .setParameter("s", eventSessionId)
                .getResultList()
                .stream()
                .findFirst()
                .orElse(null);
        long activeIssued = entityManager.createQuery("""
                SELECT COUNT(t) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s AND t.issuedAt IS NOT NULL AND t.revokedAt IS NULL
                """, Long.class)
                .setParameter("s", eventSessionId)
                .getSingleResult();
        return new OccupancyInputs(capacity, activeIssued);
    }

    // ------------------------------------------------------------------
    // Freshness anchor (order-independent)
    // ------------------------------------------------------------------

    /**
     * Max source-event time over every fact attributed to one session. Facts converge under
     * replay/reordering, so this max is delivery-order independent — unlike folding each
     * triggering event's time in arrival order. Used for {@code last_projected_event_at}.
     */
    public Instant maxSessionFactTime(UUID eventSessionId) {
        Instant reservations = maxInstant("""
                SELECT MAX(r.lastSourceEventAt) FROM AnalyticsReservationFact r
                WHERE r.eventSessionId = :s
                """, eventSessionId);
        Instant payments = maxInstant("""
                SELECT MAX(p.lastSourceEventAt) FROM AnalyticsPaymentFact p
                WHERE p.eventSessionId = :s
                """, eventSessionId);
        Instant tickets = maxInstant("""
                SELECT MAX(t.lastSourceEventAt) FROM AnalyticsTicketFact t
                WHERE t.eventSessionId = :s
                """, eventSessionId);
        Instant best = reservations;
        if (payments != null && (best == null || payments.isAfter(best))) {
            best = payments;
        }
        if (tickets != null && (best == null || tickets.isAfter(best))) {
            best = tickets;
        }
        return best;
    }

    private Instant maxInstant(String jpql, UUID sessionId) {
        return entityManager.createQuery(jpql, Instant.class)
                .setParameter("s", sessionId)
                .getSingleResult();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private long count(String jpql, UUID sessionId) {
        return entityManager.createQuery(jpql, Long.class)
                .setParameter("s", sessionId)
                .getSingleResult();
    }

    private long countInRange(String jpql, Instant from, Instant to, UUID sessionId) {
        return entityManager.createQuery(jpql, Long.class)
                .setParameter("s", sessionId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
    }

    private long countInRange(
            String jpql, Instant from, Instant to, UUID eventId, UUID sessionId) {
        return entityManager.createQuery(jpql, Long.class)
                .setParameter("e", eventId)
                .setParameter("s", sessionId)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
    }

    private long cohortCount(
            String jpql, Instant from, Instant to, UUID eventId, UUID sessionId) {
        return entityManager.createQuery(jpql, Long.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setParameter("e", eventId)
                .setParameter("s", sessionId)
                .getSingleResult();
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }
}
