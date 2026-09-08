package com.seatflow.ai.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI-owned transport view of a Reservation Service reservation
 * ({@code GET /api/reservations/{id}}, {@code POST /api/reservations}).
 *
 * <p>Unknown wire fields are ignored so additive upstream changes cannot break AI reads. Customer
 * email, guest proofs, internal provider IDs, payment tokens, and audit internals are not mapped
 * here even when the wire carries them — the AI layer must never forward them to the model or
 * the confirmation card.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationServiceReservationDto(
        UUID id,
        UUID eventSessionId,
        UUID eventId,
        String status,
        Instant expiresAt,
        BigDecimal totalAmount,
        Integer seatCount,
        Instant sessionStartsAt,
        Instant sessionEndsAt,
        String sessionTimezone,
        Instant createdAt,
        List<ReservationServiceSeatDto> seats
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReservationServiceSeatDto(
            UUID seatId,
            String status,
            BigDecimal price,
            String rowNumber,
            Integer seatNumber,
            UUID pricingTierId,
            String ticketType
    ) {}
}
