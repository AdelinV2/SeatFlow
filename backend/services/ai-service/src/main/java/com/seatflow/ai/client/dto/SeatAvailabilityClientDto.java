package com.seatflow.ai.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/**
 * AI-owned transport view of the session-scoped seat availability
 * ({@code GET /api/event-sessions/{sessionId}/seats/availability}).
 *
 * <p>The response lists seats that are currently <em>not</em> available (held/sold). A seat from
 * the seat map is AVAILABLE exactly when it is absent from this list.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatAvailabilityClientDto(
        UUID eventSessionId,
        UUID eventId,
        List<SeatStatusEntry> seatStatuses
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatStatusEntry(
            UUID seatId,
            String status
    ) {}
}
