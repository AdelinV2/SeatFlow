package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One projected event option for {@code GET /api/admin/analytics/filter-options/events}
 * (TASK-P14-006 §6.2).
 *
 * <p>Sourced solely from the {@code seatflow_analytics} read model: only events with
 * operational rows inside the requested UTC range are listed. The label is an analytics-owned
 * title snapshot and may be {@code null}; the dashboard falls back to the event ID.
 * No Event Service lookup is performed.
 */
@Schema(description = "Projected event filter option sourced from analytics facts only")
public record AnalyticsEventFilterOptionResponse(
        @Schema(description = "Projected event ID") UUID eventId,
        @Schema(description = "Event title snapshot; null until a trusted lifecycle event provides it",
                nullable = true) String label,
        @Schema(description = "Earliest projected session start for this event; null when unknown",
                nullable = true) Instant firstProjectedSessionStart) {
}
