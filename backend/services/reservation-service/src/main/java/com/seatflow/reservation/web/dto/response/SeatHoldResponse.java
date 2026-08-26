package com.seatflow.reservation.web.dto.response;

import com.seatflow.reservation.model.enums.SeatHoldStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record SeatHoldResponse(

        UUID id,
        UUID seatId,
        SeatHoldStatus status,
        BigDecimal price
) {
}
