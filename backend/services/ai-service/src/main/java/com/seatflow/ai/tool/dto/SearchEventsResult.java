package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Bounded, customer-safe result of the {@code searchEvents} AI tool.
 */
@Schema(description = "Authoritative snapshot of matching published events")
public record SearchEventsResult(

        @Schema(description = "Matching events, at most the requested (clamped) limit")
        List<EventSearchItem> events
) {
    public SearchEventsResult {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
