package com.seatflow.payment.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SeatHoldClientDto(
        UUID id,
        UUID seatId,
        String status,
        BigDecimal price
) {
}
