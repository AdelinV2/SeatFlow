package com.seatflow.ai.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * AI-owned outbound view of the canonical reservation creation request
 * ({@code POST /api/reservations}).
 *
 * <p>All values are server-derived from the stored secure proposal plus live revalidation —
 * never from model prose or client-editable confirmation fields. Authenticated users omit
 * {@code customerEmail} so Reservation Service resolves it from the caller JWT (ADR-001);
 * the AI flow never fabricates a guest email proof.
 */
public record CreateReservationServiceRequest(
        UUID eventSessionId,
        List<UUID> seatIds,
        List<BigDecimal> seatPrices,
        String idempotencyKey
) {
    public CreateReservationServiceRequest {
        if (eventSessionId == null) {
            throw new IllegalArgumentException("eventSessionId is required");
        }
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > 10) {
            throw new IllegalArgumentException("seatIds must contain between 1 and 10 entries");
        }
        if (seatPrices == null || seatPrices.size() != seatIds.size()) {
            throw new IllegalArgumentException("seatPrices size must match seatIds size");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        seatIds = List.copyOf(seatIds);
        seatPrices = List.copyOf(seatPrices);
    }
}
