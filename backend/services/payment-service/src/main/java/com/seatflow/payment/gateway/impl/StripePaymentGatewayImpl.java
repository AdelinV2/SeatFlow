package com.seatflow.payment.gateway.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.payment.config.StripeConfig;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.seatflow.payment.gateway.dto.StripeTaxResult;
import com.seatflow.payment.gateway.dto.TaxAddress;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.tax.Calculation;
import com.stripe.net.RequestOptions;
import com.stripe.param.tax.CalculationCreateParams;
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

        validateStripeTestConfiguration();

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
                    );

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

    @Override
    public StripeTaxResult calculateInclusiveTax(BigDecimal amount,
                                                 String currency,
                                                 String reference,
                                                 TaxAddress address) {
        validateStripeTestConfiguration();
        try {
            long amountInCents = amount.multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
            CalculationCreateParams.CustomerDetails.Address stripeAddress =
                    CalculationCreateParams.CustomerDetails.Address.builder()
                            .setLine1(address.line1())
                            .setLine2(address.line2())
                            .setCity(address.city())
                            .setState(address.state())
                            .setPostalCode(address.postalCode())
                            .setCountry(address.country().toUpperCase(java.util.Locale.ROOT))
                            .build();
            CalculationCreateParams params = CalculationCreateParams.builder()
                    .setCurrency(currency.toLowerCase(java.util.Locale.ROOT))
                    .setCustomerDetails(CalculationCreateParams.CustomerDetails.builder()
                            .setAddress(stripeAddress)
                            .setAddressSource(CalculationCreateParams.CustomerDetails.AddressSource.BILLING)
                            .build())
                    .addLineItem(CalculationCreateParams.LineItem.builder()
                            .setAmount(amountInCents)
                            .setQuantity(1L)
                            .setReference(reference)
                            .setTaxBehavior(CalculationCreateParams.LineItem.TaxBehavior.INCLUSIVE)
                            .build())
                    .build();
            Calculation calculation = Calculation.create(params,
                    RequestOptions.builder().setApiKey(stripeConfig.getApiKey()).build());
            long taxCents = calculation.getTaxAmountInclusive() == null ? 0L : calculation.getTaxAmountInclusive();
            BigDecimal taxAmount = BigDecimal.valueOf(taxCents, 2);
            BigDecimal netAmount = amount.subtract(taxAmount);
            BigDecimal effectiveRate = netAmount.signum() > 0
                    ? taxAmount.multiply(BigDecimal.valueOf(100)).divide(netAmount, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new StripeTaxResult(taxAmount, effectiveRate, currency.toUpperCase(java.util.Locale.ROOT));
        } catch (StripeException ex) {
            log.warn("Stripe Tax preview failed. reference={}", reference, ex);
            String message = ex.getUserMessage() != null ? ex.getUserMessage() : ex.getMessage();
            throw new BusinessException("Stripe Tax preview failed: " + message, ErrorCode.PAYMENT_FAILED, 502);
        }
    }

    private void validateStripeTestConfiguration() {
        String apiKey = stripeConfig.getApiKey();
        String normalized = apiKey == null ? "" : apiKey.trim();
        String lowerCase = normalized.toLowerCase(java.util.Locale.ROOT);
        boolean placeholder = lowerCase.contains("dummy")
                || lowerCase.contains("mock")
                || lowerCase.contains("replace")
                || lowerCase.contains("your_");
        if (normalized.startsWith("pk_test_")) {
            throw new BusinessException(
                    "Stripe backend is configured with a publishable key (pk_test_...). Set STRIPE_API_KEY to the Stripe secret key (sk_test_...) in payment-service/.env and restart the service.",
                    ErrorCode.PAYMENT_FAILED,
                    502);
        }
        if (!normalized.startsWith("sk_test_") || placeholder) {
            throw new BusinessException(
                    "Stripe test mode is not configured. Set STRIPE_API_KEY to a valid sk_test_ key in payment-service/.env and restart the service.",
                    ErrorCode.PAYMENT_FAILED,
                    502);
        }
    }
}
