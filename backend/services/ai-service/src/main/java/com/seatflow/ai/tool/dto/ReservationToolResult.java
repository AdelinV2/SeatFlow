package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Compact customer-safe reservation lookup result (TASK-P15-005 section 3).
 *
 * <p>Contains only what the user needs to reconcile a hold: IDs, status, expiry, canonical
 * total/currency, seat display summary, and session start metadata when the public reservation
 * response carries it. Customer email/name, guest proofs, internal provider IDs, payment tokens,
 * and audit internals are never exposed — prefer omission.
 */
@Schema(description = "Compact customer-safe reservation snapshot for the AI assistant")
public record ReservationToolResult(

        @Schema(description = "Reservation UUID") UUID reservationId,
        @Schema(description = "Parent event UUID") UUID eventId,
        @Schema(description = "Authoritative event session UUID") UUID eventSessionId,
        @Schema(description = "Authoritative reservation status") String status,
        @Schema(description = "Hold expiry instant (UTC, authoritative)") Instant expiresAt,
        @Schema(description = "Authoritative total amount") BigDecimal totalAmount,
        @Schema(description = "ISO-4217 currency for the total") String currency,
        @Schema(description = "Seat display summary") List<ReservationSeatDisplay> seats,
        @Schema(description = "Immutable session start captured at hold time, when present")
        Instant sessionStartsAt,
        @Schema(description = "Immutable session end captured at hold time, when present")
        Instant sessionEndsAt,
        @Schema(description = "Nullable session timezone metadata, when present")
        String sessionTimezone
) {
    @Schema(description = "One held seat display entry")
    public record ReservationSeatDisplay(
            @Schema(description = "Seat UUID") UUID seatId,
            @Schema(description = "Human-readable label, e.g. 'Orchestra Row A Seat 12'") String label,
            @Schema(description = "Row label when present") String rowLabel,
            @Schema(description = "Seat number when present") Integer seatNumber
    ) {}

    public ReservationToolResult {
        seats = seats == null ? List.of() : List.copyOf(seats);
    }
}
