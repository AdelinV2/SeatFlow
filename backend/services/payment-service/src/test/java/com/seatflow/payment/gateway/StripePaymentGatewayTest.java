package com.seatflow.payment.gateway.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.payment.config.StripeConfig;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.stripe.StripeClient;
import com.stripe.exception.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import com.stripe.service.PaymentIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripePaymentGatewayTest {

    @Mock
    private StripeConfig stripeConfig;

    @Mock
    private StripeClient stripeClient;

    @Mock
    private PaymentIntentService paymentIntentService;

    private StripePaymentGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        gateway = new StripePaymentGatewayImpl(stripeConfig, stripeClient);
        lenient().when(stripeClient.paymentIntents()).thenReturn(paymentIntentService);
    }

    @Test
    void createPaymentIntentConvertsAmountToCentsAndForwardsMetadata() throws Exception {
        when(stripeConfig.getApiKey()).thenReturn("sk_test_xxx");

        PaymentIntent mockIntent = mock(PaymentIntent.class);
        when(mockIntent.getId()).thenReturn("pi_123");
        when(mockIntent.getClientSecret()).thenReturn("pi_123_secret");
        when(mockIntent.getStatus()).thenReturn("requires_payment_method");

        ArgumentCaptor<PaymentIntentCreateParams> paramsCaptor = ArgumentCaptor.forClass(PaymentIntentCreateParams.class);

        when(paymentIntentService.create(paramsCaptor.capture(), any(RequestOptions.class)))
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
        assertThat(captured.getExtraParams()).isNull();
    }

    @Test
    void createPaymentIntentRejectsMissingOrLiveStripeKeyBeforeCallingStripe() {
        when(stripeConfig.getApiKey()).thenReturn("pk_test_not_a_secret_key");

        assertThatThrownBy(() -> gateway.createPaymentIntent(
                new BigDecimal("10.00"), "USD", "idem-invalid-key", Map.of(), "a@b.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("publishable key")
                .hasMessageContaining("sk_test_")
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED));
    }

    @Test
    void updatePaymentIntentConvertsAmountToCentsAndForwardsMetadata() throws Exception {
        when(stripeConfig.getApiKey()).thenReturn("sk_test_xxx");

        PaymentIntent existingIntent = mock(PaymentIntent.class);
        PaymentIntent updatedIntent = mock(PaymentIntent.class);
        when(updatedIntent.getId()).thenReturn("pi_123");
        when(updatedIntent.getClientSecret()).thenReturn("pi_123_secret");
        when(updatedIntent.getStatus()).thenReturn("requires_payment_method");
        when(existingIntent.getId()).thenReturn("pi_123");
        ArgumentCaptor<PaymentIntentUpdateParams> paramsCaptor = ArgumentCaptor.forClass(PaymentIntentUpdateParams.class);
        when(paymentIntentService.retrieve("pi_123")).thenReturn(existingIntent);
        when(paymentIntentService.update(eq("pi_123"), paramsCaptor.capture())).thenReturn(updatedIntent);

        StripeIntentResult result = gateway.updatePaymentIntent(
                "pi_123", new BigDecimal("59.95"), "USD",
                Map.of("reservationId", "r1"), "a@b.com");

        assertThat(result.paymentIntentId()).isEqualTo("pi_123");
        assertThat(result.clientSecret()).isEqualTo("pi_123_secret");
        assertThat(result.status()).isEqualTo("requires_payment_method");
        assertThat(paramsCaptor.getValue().getAmount()).isEqualTo(5995L);
        assertThat(paramsCaptor.getValue().getCurrency()).isEqualTo("usd");
        assertThat(String.valueOf(((Map<?, ?>) paramsCaptor.getValue().getMetadata()).get("reservationId")))
                .isEqualTo("r1");
        assertThat(paramsCaptor.getValue().getReceiptEmail()).isEqualTo("a@b.com");
    }

    @Test
    void createPaymentIntentMapsStripeExceptionToBusinessException() throws Exception {
        when(stripeConfig.getApiKey()).thenReturn("sk_test_xxx");

        when(paymentIntentService.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiException("boom", "req_1", "card_error", 402, null));

        assertThatThrownBy(() -> gateway.createPaymentIntent(
                new BigDecimal("10.00"), "USD", "idem-2", Map.of(), "a@b.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED));
    }
}
