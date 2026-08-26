package com.seatflow.payment.web.dto.response;

import com.seatflow.payment.model.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Response returned upon Stripe PaymentIntent creation with client secret for Stripe Elements")
public record PaymentIntentResponse(
    @Schema(description = "Payment aggregate identifier") UUID paymentId,
    @Schema(description = "Stripe client secret used by the frontend Stripe Elements SDK") String clientSecret,
    @Schema(description = "Total payment amount") BigDecimal amount,
    @Schema(description = "Payment currency ISO code", example = "USD") String currency,
    @Schema(description = "Current payment status") PaymentStatus status
) {}
