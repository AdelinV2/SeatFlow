package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Typed input for the {@code getEventSessions} AI tool.
 */
@Schema(description = "Lookup key plus optional bounds for booking-visible event sessions")
public record GetEventSessionsRequest(

        @Schema(description = "Event UUID, normally taken from a searchEvents result",
                example = "123e4567-e89b-12d3-a456-426614174000")
        String eventId,

        @Schema(description = "Optional inclusive lower bound on session start (ISO-8601 instant)")
        Instant from,

        @Schema(description = "Optional inclusive upper bound on session start; must be on or after from")
        Instant to,

        @Schema(description = "Maximum sessions; server-clamped to 1..50, defaults to 20", example = "20")
        Integer limit
) {}
