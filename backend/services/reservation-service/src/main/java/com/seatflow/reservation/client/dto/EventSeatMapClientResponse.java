package com.seatflow.reservation.client.dto;

import java.time.Instant;
import java.util.UUID;

public record EventSeatMapClientResponse(
        String status,
        UUID eventId,
        String name,
        Instant eventDate,
        UUID venueId,
        SeatMapSectionClientDto seatMap
) {
}
