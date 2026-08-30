package com.seatflow.reservation.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SeatMapSeatClientDto(
        UUID seatId,
        String rowLabel,
        Integer seatNumber,
        Integer gridX,
        Integer gridY,
        Boolean isActive
) {
}
