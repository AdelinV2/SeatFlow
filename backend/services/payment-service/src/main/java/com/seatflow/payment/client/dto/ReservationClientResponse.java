package com.seatflow.payment.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationClientResponse(
        UUID id,
        UUID eventSessionId,
        UUID eventId,
        UUID userId,
        String customerEmail,
        String status,
        Instant expiresAt,
        BigDecimal totalAmount,
        Integer seatCount,
        Instant sessionStartsAt,
        Instant sessionEndsAt,
        String sessionTimezone,
        List<SeatHoldClientDto> seats,
        Instant createdAt
) {
}
