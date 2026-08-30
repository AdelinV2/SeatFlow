package com.seatflow.payment.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Stripe Tax preview for a tax-inclusive payment")
public record TaxPreviewResponse(
        BigDecimal taxAmount,
        BigDecimal effectiveRate,
        String currency
) {
}

