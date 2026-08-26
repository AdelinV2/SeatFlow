package com.seatflow.reservation.web.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateReservationRequest(

        @NotNull(message = "eventId is required")
        UUID eventId,

        @Email(message = "customerEmail must be a valid email")
        @NotBlank(message = "customerEmail is required")
        String customerEmail,

        String customerName,

        @NotNull(message = "seatIds are required")
        @Size(min = 1, max = 10, message = "seatIds must contain between 1 and 10 entries")
        java.util.List<UUID> seatIds,

        @NotNull(message = "seatPrices are required")
        @Size(min = 1, max = 10, message = "seatPrices must contain between 1 and 10 entries")
        java.util.List<@Positive(message = "seatPrices entries must be positive") BigDecimal> seatPrices,

        @NotBlank(message = "idempotencyKey is required")
        @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
        String idempotencyKey
) {
}
