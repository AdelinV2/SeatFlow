package com.seatflow.payment.gateway;

import com.seatflow.payment.gateway.dto.StripeIntentResult;

import java.math.BigDecimal;
import java.util.Map;

public interface StripePaymentGateway {

    StripeIntentResult createPaymentIntent(
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            Map<String, String> metadata,
            String customerEmail
    );
}
