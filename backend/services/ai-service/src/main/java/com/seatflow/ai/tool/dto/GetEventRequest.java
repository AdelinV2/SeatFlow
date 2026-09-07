package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Typed input for the {@code getEvent} AI tool.
 *
 * <p>The identifier is transported as a string so malformed values fail with the deterministic
 * {@code INVALID_TOOL_ARGUMENT} category before any downstream call.
 */
@Schema(description = "Lookup key for one published event")
public record GetEventRequest(

        @Schema(description = "Event UUID, normally taken from a searchEvents result",
                example = "123e4567-e89b-12d3-a456-426614174000")
        String eventId
) {}
