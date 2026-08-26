package com.seatflow.reservation.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SeatMapSeatClientDto(
        UUID seatId,
        String seatNumber,
        String rowLabel,
        String seatStatus,
        UUID pricingTierId,
        BigDecimal price
) {
}
