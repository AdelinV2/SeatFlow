package com.seatflow.reservation.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventSeatMapClientResponse(
        UUID eventId,
        UUID venueId,
        String eventTitle,
        String status,
        Instant eventDate,
        String venueName,
        Integer venueCapacity,
        Long totalConfiguredSeats,
        List<SeatMapSectionClientDto> sections
) {
}
