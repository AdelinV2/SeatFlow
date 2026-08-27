package com.seatflow.payment.gateway.dto;

public record StripeIntentResult(
        String paymentIntentId,
        String clientSecret,
        String status
) {
}
