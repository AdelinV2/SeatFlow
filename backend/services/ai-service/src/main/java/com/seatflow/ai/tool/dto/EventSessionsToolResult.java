package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Bounded, customer-safe result of the {@code getEventSessions} AI tool.
 */
@Schema(description = "Authoritative snapshot of booking-visible sessions for one event")
public record EventSessionsToolResult(

        @Schema(description = "Parent event UUID") UUID eventId,
        @Schema(description = "Booking-visible sessions, at most the requested (clamped) limit")
        List<SessionToolItem> sessions
) {
    public EventSessionsToolResult {
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }
}
