package com.seatflow.event.client;

import java.util.List;
import java.util.UUID;

public record SeatMapVenueSection(
        UUID sectionId,
        String name,
        Integer rowCount,
        Integer colCount,
        List<SeatMapVenueSeat> seats
) {
}
