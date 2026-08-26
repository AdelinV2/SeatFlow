package com.seatflow.reservation.web.dto.response;

import com.seatflow.reservation.model.enums.SeatHoldStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Seat status tuple")
public record EventSeatStatusResponse(

        @Schema(description = "Seat unique identifier") UUID seatId,

        @Schema(description = "Live status (HELD, SOLD)") SeatHoldStatus status
) {
}
