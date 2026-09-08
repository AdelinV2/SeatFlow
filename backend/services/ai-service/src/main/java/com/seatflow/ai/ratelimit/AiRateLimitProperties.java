package com.seatflow.ai.ratelimit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounded per-user AI rate-limit configuration (TASK-P15-007 section 11).
 *
 * <p>Defaults protect a free provider tier without encoding any Groq-specific quota as a business
 * invariant: {@code AI_CHAT_RATE_LIMIT_PER_MINUTE=60} chat turns and
 * {@code AI_CONFIRM_RATE_LIMIT_PER_MINUTE=30} confirmation attempts per authenticated user per
 * minute, tracked for at most {@code AI_RATE_LIMIT_MAX_USERS=5000} users in memory
 * (single-instance portfolio deployment, no new infrastructure product).
 *
 * <p>Only AI endpoints are guarded (see {@link AiRateLimitFilter}); normal SeatFlow APIs are
 * unaffected. Confirmation retries within budget always pass, so safe idempotent retry semantics
 * are preserved.
 */
@Validated
@ConfigurationProperties(prefix = "seatflow.ai.rate-limit")
public record AiRateLimitProperties(

        /** Chat turns per authenticated user per fixed minute window. */
        @Min(1) @Max(10000) int chatRequestsPerMinute,

        /** Confirmation attempts per authenticated user per fixed minute window. */
        @Min(1) @Max(10000) int confirmAttemptsPerMinute,

        /** Upper bound on tracked user buckets; oldest-idle entries are evicted past this. */
        @Min(100) @Max(100000) int maxTrackedUsers
) {
}
