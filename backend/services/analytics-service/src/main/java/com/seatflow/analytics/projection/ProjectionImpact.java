package com.seatflow.analytics.projection;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate keys affected by one projected source event (TASK-P14-003).
 *
 * <p>Fact reducers collect old and new keys (correlation can move unknown → known or, for a
 * valid correction, session A → B); the reconciler recomputes every collected key from facts so
 * no stale contribution survives. Financial daily keys cover both the completion date and the
 * (possibly later) refund date of one payment.
 */
public record ProjectionImpact(
        Set<SessionKey> sessions,
        Set<DailyKey> dailyOperational,
        Set<SessionCurrencyKey> sessionRevenue,
        Set<DailyCurrencyKey> dailyRevenue) {

    public ProjectionImpact {
        sessions = Set.copyOf(sessions);
        dailyOperational = Set.copyOf(dailyOperational);
        sessionRevenue = Set.copyOf(sessionRevenue);
        dailyRevenue = Set.copyOf(dailyRevenue);
    }

    public static ProjectionImpact empty() {
        return new ProjectionImpact(Set.of(), Set.of(), Set.of(), Set.of());
    }

    /** Union of two impacts (for correlation moves that must recompute old and new keys). */
    public ProjectionImpact merge(ProjectionImpact other) {
        Set<SessionKey> sessions = new HashSet<>(this.sessions);
        sessions.addAll(other.sessions);
        Set<DailyKey> dailyOperational = new HashSet<>(this.dailyOperational);
        dailyOperational.addAll(other.dailyOperational);
        Set<SessionCurrencyKey> sessionRevenue = new HashSet<>(this.sessionRevenue);
        sessionRevenue.addAll(other.sessionRevenue);
        Set<DailyCurrencyKey> dailyRevenue = new HashSet<>(this.dailyRevenue);
        dailyRevenue.addAll(other.dailyRevenue);
        return new ProjectionImpact(sessions, dailyOperational, sessionRevenue, dailyRevenue);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Operational session key: currency-neutral. */
    public record SessionKey(UUID eventId, UUID eventSessionId) {
    }

    /** Operational daily key: UTC date + event + session, currency-neutral. */
    public record DailyKey(LocalDate metricDate, UUID eventId, UUID eventSessionId) {
    }

    /** Financial session key: session + currency. */
    public record SessionCurrencyKey(UUID eventSessionId, String currency) {
    }

    /** Financial daily key: UTC date + event + session + currency. */
    public record DailyCurrencyKey(LocalDate metricDate, UUID eventId, UUID eventSessionId, String currency) {
    }

    public static final class Builder {
        private final Set<SessionKey> sessions = new HashSet<>();
        private final Set<DailyKey> dailyOperational = new HashSet<>();
        private final Set<SessionCurrencyKey> sessionRevenue = new HashSet<>();
        private final Set<DailyCurrencyKey> dailyRevenue = new HashSet<>();

        public Builder session(UUID eventId, UUID eventSessionId) {
            if (eventId != null && eventSessionId != null) {
                sessions.add(new SessionKey(eventId, eventSessionId));
            }
            return this;
        }

        public Builder daily(LocalDate metricDate, UUID eventId, UUID eventSessionId) {
            if (metricDate != null && eventId != null && eventSessionId != null) {
                dailyOperational.add(new DailyKey(metricDate, eventId, eventSessionId));
            }
            return this;
        }

        public Builder sessionRevenue(UUID eventSessionId, String currency) {
            if (eventSessionId != null && currency != null) {
                sessionRevenue.add(new SessionCurrencyKey(eventSessionId, currency));
            }
            return this;
        }

        public Builder dailyRevenue(LocalDate metricDate, UUID eventId, UUID eventSessionId, String currency) {
            if (metricDate != null && eventId != null && eventSessionId != null && currency != null) {
                dailyRevenue.add(new DailyCurrencyKey(metricDate, eventId, eventSessionId, currency));
            }
            return this;
        }

        public ProjectionImpact build() {
            return new ProjectionImpact(sessions, dailyOperational, sessionRevenue, dailyRevenue);
        }
    }
}
