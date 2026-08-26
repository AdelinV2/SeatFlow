package com.seatflow.reservation.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Real-time seat availability response for an event")
public record SeatAvailabilityResponse(

        @Schema(description = "Target event identifier") UUID eventId,

        @Schema(description = "List of current seat statuses") List<EventSeatStatusResponse> seatStatuses
) {
}
