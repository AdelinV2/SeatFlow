package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One ranking entry for {@code GET /api/admin/analytics/top} (TASK-P14-004 §6.6).
 *
 * <p>{@code value} is a currency-neutral count for count rankings, or integer minor-unit net
 * revenue for {@code NET_REVENUE} (which always carries exactly one {@code currency}).
 * Tie-break is stable: metric DESC, {@code eventSessionId} ASC.
 */
@Schema(description = "One deterministic top-session ranking entry")
public record TopAnalyticsItemResponse(
        @Schema(description = "Owning event ID") UUID eventId,
        @Schema(description = "Event session ID") UUID eventSessionId,
        @Schema(description = "Event title snapshot; null when unknown", nullable = true) String eventTitle,
        @Schema(description = "Session label snapshot; null when unknown", nullable = true) String sessionLabel,
        @Schema(description = "Session start time; null when unknown", nullable = true) Instant startsAt,
        @Schema(description = "Ranking metric", example = "NET_REVENUE") String metric,
        @Schema(description = "Count, or minor-unit net revenue for NET_REVENUE", example = "3030000") long value,
        @Schema(description = "Currency for NET_REVENUE; null for count rankings",
                example = "RON", nullable = true) String currency) {
}
