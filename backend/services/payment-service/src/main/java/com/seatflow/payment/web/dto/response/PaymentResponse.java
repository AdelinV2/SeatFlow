package com.seatflow.payment.web.dto.response;

import com.seatflow.payment.model.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Detailed payment status response")
public record PaymentResponse(
    @Schema(description = "Unique payment identifier") UUID id,
    @Schema(description = "Target reservation identifier") UUID reservationId,
    @Schema(description = "Customer user identifier (null for guests)") UUID userId,
    @Schema(description = "Customer email address") String customerEmail,
    @Schema(description = "Target event identifier") UUID eventId,
    @Schema(description = "Stripe PaymentIntent ID") String stripePaymentIntentId,
    @Schema(description = "Payment amount") BigDecimal amount,
    @Schema(description = "Tax included in the payment amount") BigDecimal taxAmount,
    @Schema(description = "Net amount excluding included tax") BigDecimal netAmount,
    @Schema(description = "Payment currency ISO code") String currency,
    @Schema(description = "Payment status") PaymentStatus status,
    @Schema(description = "Failure reason if payment was unsuccessful") String failureReason,
    @Schema(description = "Payment creation timestamp") Instant createdAt,
    @Schema(description = "Payment last updated timestamp") Instant updatedAt
) {}
