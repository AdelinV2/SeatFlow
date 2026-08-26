package com.seatflow.payment.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Schema(description = "Request body for creating a Stripe PaymentIntent for an active reservation")
public record CreatePaymentIntentRequest(

    @Schema(description = "UUID of the pending reservation to pay for", example = "123e4567-e89b-12d3-a456-426614174000", requiredMode = REQUIRED)
    @NotNull(message = "Reservation ID is required")
    UUID reservationId,

    @Schema(description = "Unique client-generated idempotency key to prevent double charges", example = "pay-req-user123-uuid-001", requiredMode = REQUIRED)
    @NotBlank(message = "Idempotency key is required")
    @Size(max = 255)
    String idempotencyKey
) {}
