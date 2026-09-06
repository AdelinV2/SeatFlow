package com.seatflow.reservation.client.dto;

import java.util.List;
import java.util.UUID;

/**
 * P12-007 wire mirror of event-service {@code EventSeatMapResponse}: venue
 * layout + pricing only. There is deliberately no event-level instant here;
 * showing schedule lives exclusively on EventSession and bookability is
 * derived from the trusted {@link SessionBookingContextDto}.
 */
public record EventSeatMapClientResponse(
        UUID eventId,
        UUID venueId,
        String eventTitle,
        String status,
        String venueName,
        Integer venueCapacity,
        Long totalConfiguredSeats,
        List<SeatMapSectionClientDto> sections
) {
}
