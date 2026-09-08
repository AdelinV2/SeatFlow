package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Read-model recency metadata attached to admin analytics responses.
 *
 * <p>The analytics read model is eventually consistent: values reflect the last projected
 * Kafka event, never transactional truth. No Kafka-lag math is exposed; during no-event
 * periods these timestamps only describe read-model recency.
 */
@Schema(description = "Projection freshness metadata (eventually consistent read model)")
public record ProjectionFreshnessResponse(
        @Schema(description = "Server time when the response was generated (UTC)", example = "2026-09-06T12:00:00Z")
        Instant generatedAt,
        @Schema(description = "Max successfully projected source-event time; null before the first event",
                example = "2026-09-06T11:59:42Z", nullable = true)
        Instant lastProjectedEventAt,
        @Schema(description = "Max processed_events.processed_at; null before the first event",
                example = "2026-09-06T11:59:43Z", nullable = true)
        Instant lastProcessedAt,
        @Schema(description = "Always true: analytics is an eventually consistent projection", example = "true")
        boolean eventuallyConsistent) {

    public static ProjectionFreshnessResponse of(
            Instant generatedAt, Instant lastProjectedEventAt, Instant lastProcessedAt) {
        return new ProjectionFreshnessResponse(generatedAt, lastProjectedEventAt, lastProcessedAt, true);
    }
}
