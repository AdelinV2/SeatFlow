package com.seatflow.reservation.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "Request body to create a 15-minute seat reservation hold. Maximum 10 seats per reservation.")
public record CreateReservationRequest(

        @Schema(description = "UUID of the target event", example = "123e4567-e89b-12d3-a456-426614174000")
        @NotNull(message = "eventId is required")
        UUID eventId,

        @Schema(description = "Contact email for the reservation. Required for guests; authenticated users may omit to use their JWT email (ADR-001).",
                example = "guest@example.com")
        @Email(message = "customerEmail must be a valid email")
        String customerEmail,

        @Schema(description = "List of seat IDs to hold. Must match seatPrices size and contain no duplicates. Max 10.")
        @NotNull(message = "seatIds are required")
        @Size(min = 1, max = 10, message = "seatIds must contain between 1 and 10 entries")
        List<@NotNull(message = "seatIds entries must not be null") UUID> seatIds,

        @Schema(description = "Client-declared per-seat prices. Must match server-authoritative pricing exactly, element-for-element.")
        @NotNull(message = "seatPrices are required")
        @Size(min = 1, max = 10, message = "seatPrices must contain between 1 and 10 entries")
        List<@NotNull(message = "seatPrices entries must not be null") @Positive(message = "seatPrices entries must be positive") BigDecimal> seatPrices,

        @Schema(description = "Idempotency key to prevent double submission", example = "idem-98765-abcd")
        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
        String idempotencyKey
) {
}
