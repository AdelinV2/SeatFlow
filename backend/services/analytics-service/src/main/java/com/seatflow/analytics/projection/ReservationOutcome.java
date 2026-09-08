package com.seatflow.analytics.projection;

import com.seatflow.analytics.model.entity.AnalyticsReservationFact;

/**
 * Analytics-only outcome classification for one reservation fact (TASK-P14-003).
 *
 * <p>Precedence {@code REFUNDED > CONFIRMED > CANCELLED > EXPIRED > CREATED/HELD} is an analytics
 * classification; it never mutates the source business state machine. Raw evidence timestamps
 * are preserved independently so stale replays cannot erase newer semantics.
 *
 * <p>Bucket mapping: {@code REFUNDED} and {@code CONFIRMED} count toward
 * {@code reservations_confirmed} (a refunded purchase remains historically confirmed);
 * {@code EXPIRED} counts toward {@code reservations_expired}; {@code CANCELLED} counts toward
 * neither (user-initiated release is terminal but is not an expiration, and V1 aggregate grain
 * has no cancellation bucket — cancelled holds stay visible in the created cohort so conversion
 * degrades honestly instead of mislabeling).
 */
public enum ReservationOutcome {

    REFUNDED,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    CREATED;

    public static ReservationOutcome classify(AnalyticsReservationFact fact) {
        if (fact.getRefundedAt() != null) {
            return REFUNDED;
        }
        if (fact.getConfirmedAt() != null) {
            return CONFIRMED;
        }
        if (fact.getCancelledAt() != null) {
            return CANCELLED;
        }
        if (fact.getExpiredAt() != null) {
            return EXPIRED;
        }
        return CREATED;
    }
}
