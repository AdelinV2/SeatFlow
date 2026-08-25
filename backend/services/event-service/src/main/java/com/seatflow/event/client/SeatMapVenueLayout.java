package com.seatflow.event.client;

import java.util.List;
import java.util.UUID;

public record SeatMapVenueLayout(
        UUID venueId,
        String name,
        Integer capacity,
        Long totalConfiguredSeats,
        List<SeatMapVenueSection> sections
) {
}
