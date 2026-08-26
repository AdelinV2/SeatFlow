package com.seatflow.reservation.web.dto.response;

import com.seatflow.reservation.model.enums.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Reservation hold confirmation response")
public record ReservationResponse(

        UUID id,
        UUID eventId,
        UUID userId,
        String customerEmail,
        ReservationStatus status,
        Instant expiresAt,
        BigDecimal totalAmount,
        Integer seatCount,
        List<SeatHoldResponse> seats,
        Instant createdAt
) {
}
