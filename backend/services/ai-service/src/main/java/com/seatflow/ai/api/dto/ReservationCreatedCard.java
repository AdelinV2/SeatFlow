package com.seatflow.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Structured reservation truth returned after explicit confirmation (TASK-P15-005 section 11).
 *
 * <p>Built from the authoritative Reservation Service response plus the validated proposal
 * display snapshot. {@code expiresAt} is the service's authoritative hold expiry — never
 * {@code now + 15m} computed locally. {@code checkoutRoute} follows application routing rules
 * ({@code /checkout/:reservationId}), never LLM text. No payment tool exists.
 */
@Schema(description = "Authoritative reservation-created card after explicit confirmation")
public record ReservationCreatedCard(

        @Schema(description = "Authoritative reservation ID") UUID reservationId,
        @Schema(description = "Authoritative event session") UUID eventSessionId,
        @Schema(description = "Held seat IDs in proposal order") List<UUID> seatIds,
        @Schema(description = "Seat display labels in proposal order") List<String> seatLabels,
        @Schema(description = "Authoritative total amount") BigDecimal totalAmount,
        @Schema(description = "Validated ISO-4217 currency") String currency,
        @Schema(description = "Authoritative reservation status") String reservationStatus,
        @Schema(description = "Authoritative hold expiry (UTC)") Instant expiresAt,
        @Schema(description = "Application checkout route, e.g. /checkout/{reservationId}")
        String checkoutRoute
) {
    public ReservationCreatedCard {
        seatIds = seatIds == null ? List.of() : List.copyOf(seatIds);
        seatLabels = seatLabels == null ? List.of() : List.copyOf(seatLabels);
    }
}
