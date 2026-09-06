package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One projected event-session option for {@code GET /api/admin/analytics/filter-options/sessions}
 * (TASK-P14-006 §6.3).
 *
 * <p>Sourced solely from the {@code seatflow_analytics} read model: only sessions with
 * operational rows inside the requested UTC range (and optional event scope) are listed.
 * The label is an analytics-owned snapshot and may be {@code null}; the dashboard falls
 * back to the session ID. No Event Service lookup is performed.
 */
@Schema(description = "Projected event-session filter option sourced from analytics facts only")
public record AnalyticsSessionFilterOptionResponse(
        @Schema(description = "Projected event session ID") UUID eventSessionId,
        @Schema(description = "Owning projected event ID") UUID eventId,
        @Schema(description = "Session label snapshot; null until a trusted lifecycle event provides it",
                nullable = true) String label,
        @Schema(description = "Session start time; null when unknown",
                nullable = true) Instant startsAt) {
}
