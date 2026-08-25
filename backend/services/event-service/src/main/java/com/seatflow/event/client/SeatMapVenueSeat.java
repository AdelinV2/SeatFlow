package com.seatflow.event.client;

import java.util.UUID;

public record SeatMapVenueSeat(
        UUID seatId,
        String rowLabel,
        Integer seatNumber,
        Integer gridX,
        Integer gridY,
        Boolean isActive
) {
}
