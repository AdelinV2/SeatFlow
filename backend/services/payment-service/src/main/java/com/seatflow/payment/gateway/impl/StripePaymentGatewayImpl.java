package com.seatflow.payment.gateway.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.payment.config.StripeConfig;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StripePaymentGatewayImpl implements StripePaymentGateway {

    private final StripeConfig stripeConfig;

    @Override
    public StripeIntentResult createPaymentIntent(
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            Map<String, String> metadata,
            String customerEmail) {

        try {
            long amountInCents = amount.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();

            PaymentIntentCreateParams.Builder paramsBuilder = PaymentIntentCreateParams.builder()
                    .setAmount(amountInCents)
                    .setCurrency(currency.toLowerCase())
                    .putAllMetadata(metadata)
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true)
                                    .build()
                    )
                    .putExtraParam("automatic_tax", Map.of("enabled", true)); // Stripe Tax calculation (ADR-004)

            if (customerEmail != null && !customerEmail.isBlank()) {
                paramsBuilder.setReceiptEmail(customerEmail);
            }

            RequestOptions requestOptions = RequestOptions.builder()
                    .setApiKey(stripeConfig.getApiKey())
                    .setIdempotencyKey(idempotencyKey)
                    .build();

            PaymentIntent paymentIntent = PaymentIntent.create(paramsBuilder.build(), requestOptions);
            log.info("Stripe PaymentIntent created. paymentIntentId={}, status={}",
                    paymentIntent.getId(), paymentIntent.getStatus());

            return new StripeIntentResult(
                    paymentIntent.getId(),
                    paymentIntent.getClientSecret(),
                    paymentIntent.getStatus()
            );

        } catch (StripeException ex) {
            log.error("Stripe API exception while creating PaymentIntent. idempotencyKey={}", idempotencyKey, ex);
            String message = ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage();
            throw new BusinessException("Payment gateway failure: " + message, ErrorCode.PAYMENT_FAILED, 502);
        }
    }
}
