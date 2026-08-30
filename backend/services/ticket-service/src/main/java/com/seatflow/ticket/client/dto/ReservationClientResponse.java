package com.seatflow.ticket.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationClientResponse(
    UUID id,
    UUID eventId,
    UUID userId,
    String customerEmail,
    String status,
    BigDecimal totalAmount,
    Integer seatCount,
    List<HeldSeatClientDto> seats,
    Instant expiresAt,
    Instant createdAt
) {
    public record HeldSeatClientDto(
        UUID id,
        UUID seatId,
        String status,
        BigDecimal price,
        String ticketType
    ) {
        public HeldSeatClientDto(UUID id, UUID seatId, String status, BigDecimal price) {
            this(id, seatId, status, price, null);
        }
    }
}
