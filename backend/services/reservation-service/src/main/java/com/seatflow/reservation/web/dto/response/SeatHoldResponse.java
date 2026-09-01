package com.seatflow.reservation.web.dto.response;

import com.seatflow.reservation.model.enums.SeatHoldStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Held seat summary response")
public record SeatHoldResponse(

        UUID id,
        UUID seatId,
        SeatHoldStatus status,
        BigDecimal price,
        String rowNumber,
        Integer seatNumber,
        UUID pricingTierId,
        String ticketType
) {
}
