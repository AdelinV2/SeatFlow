package com.seatflow.reservation.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Real-time seat availability response for one event session")
public record SeatAvailabilityResponse(

        @Schema(description = "Target event session identifier (authoritative inventory key)") UUID eventSessionId,

        @Schema(description = "Parent event identifier, derived from stored holds (display only, null when no holds exist)")
        UUID eventId,

        @Schema(description = "List of current seat statuses") List<EventSeatStatusResponse> seatStatuses
) {
}
