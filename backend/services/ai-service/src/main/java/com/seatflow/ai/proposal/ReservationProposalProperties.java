package com.seatflow.ai.proposal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bounded in-memory proposal store properties (TASK-P15-005 section 5).
 *
 * <p>Defaults: {@code AI_PROPOSAL_TTL=5m}, {@code AI_MAX_ACTIVE_PROPOSALS=500} for the current
 * single-instance portfolio deployment. Both are configurable with validation and safe upper
 * bounds. The TTL is explicitly not a seat hold; seats stay free until Reservation Service
 * creates the normal 15-minute hold.
 */
@Validated
@ConfigurationProperties(prefix = "seatflow.ai.proposal")
public record ReservationProposalProperties(

        /**
         * Proposal time-to-live since creation. Expired proposals are invalid and cleaned up
         * lazily and/or by bounded scheduled cleanup. Minimum 1 minute, maximum 30 minutes.
         */
        Duration ttl,

        /**
         * Maximum stored proposals for the single-instance deployment. When reached after
         * expired-entry cleanup, creation of non-active garbage is evicted deterministically;
         * active proposals of other users are never evicted to make room. Safe upper bound 5000.
         */
        @Min(1) @Max(5000) int maxActiveProposals,

        /**
         * Cadence hint for scheduled cleanup logging only; the scheduler reads its own interval
         * property. Declared here so all proposal bounds stay discoverable in one place.
         */
        Duration cleanupInterval
) {
    public ReservationProposalProperties {
        if (ttl == null) {
            throw new IllegalArgumentException("Proposal TTL is required");
        }
        if (ttl.compareTo(Duration.ofMinutes(1)) < 0
                || ttl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException(
                    "Proposal TTL must be between 1 minute and 30 minutes inclusive");
        }
        if (maxActiveProposals < 1 || maxActiveProposals > 5000) {
            throw new IllegalArgumentException(
                    "Max active proposals must be between 1 and 5000 inclusive");
        }
        if (cleanupInterval == null) {
            cleanupInterval = Duration.ofMinutes(1);
        }
    }
}
