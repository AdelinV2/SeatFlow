package com.seatflow.payment.gateway.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.payment.config.StripeConfig;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.stripe.exception.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripePaymentGatewayTest {

    @Mock
    private StripeConfig stripeConfig;

    private StripePaymentGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        gateway = new StripePaymentGatewayImpl(stripeConfig);
    }

    @Test
    void createPaymentIntentConvertsAmountToCentsAndForwardsMetadata() {
        when(stripeConfig.getApiKey()).thenReturn("sk_test_xxx");

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_123");
        when(mockIntent.getClientSecret()).thenReturn("pi_123_secret");
        when(mockIntent.getStatus()).thenReturn("requires_payment_method");

        ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.create(paramsCaptor.capture(), any(RequestOptions.class)))
                    .thenReturn(mockIntent);

            StripeIntentResult result = gateway.createPaymentIntent(
                    new BigDecimal("49.99"), "USD", "idem-1",
                    Map.of("reservationId", "r1", "customerEmail", "a@b.com"), "a@b.com");

            assertThat(result.paymentIntentId()).isEqualTo("pi_123");
            assertThat(result.clientSecret()).isEqualTo("pi_123_secret");
            assertThat(result.status()).isEqualTo("requires_payment_method");

            PaymentIntentCreateParams captured = paramsCaptor.getValue();
            assertThat(captured.getAmount()).isEqualTo(4999L);
            assertThat(captured.getCurrency()).isEqualTo("usd");
            assertThat(captured.getMetadata()).containsEntry("reservationId", "r1");
            assertThat(captured.getMetadata()).containsEntry("customerEmail", "a@b.com");
            assertThat(captured.getExtraParams()).containsEntry("automatic_tax", Map.of("enabled", true));
        }
    }

    @Test
    void createPaymentIntentMapsStripeExceptionToBusinessException() {
        when(stripeConfig.getApiKey()).thenReturn("sk_test_xxx");

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiException("boom", "req_1", "card_error", 402, null));

            assertThatThrownBy(() -> gateway.createPaymentIntent(
                    new BigDecimal("10.00"), "USD", "idem-2", Map.of(), "a@b.com"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED));
        }
    }
}
