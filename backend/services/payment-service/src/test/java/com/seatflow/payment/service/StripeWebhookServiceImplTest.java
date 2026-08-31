package com.seatflow.payment.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.JsonObject;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.payment.config.StripeConfig;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.repository.OutboxEventRepository;
import com.seatflow.payment.repository.PaymentRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.net.Webhook;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookServiceImplTest {

    @Mock
    private StripeConfig stripeConfig;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private StripeWebhookServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StripeWebhookServiceImpl(stripeConfig, paymentRepository, outboxEventRepository, objectMapper,
                meterRegistry, mock(W3cTraceContextPropagator.class));
        when(stripeConfig.getWebhookSecret()).thenReturn("whsec_test");
    }

    private Event paymentIntentEvent(String type, PaymentIntent paymentIntent) {
        Event event = mock(Event.class);
        when(event.getType()).thenReturn(type);
        when(event.getId()).thenReturn("evt_1");
        EventDataObjectDeserializer deser = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deser);
        when(deser.getObject()).thenReturn(Optional.of(paymentIntent));
        return event;
    }

    @Test
    void succeededTransitionCreatesPaymentCompletedOutbox() throws Exception {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_123");

        // Stripe Tax (ADR-004) is only available from the raw EVENT JSON. Stripe returns
        // `amount_details.tax` as a JSON ARRAY of line items, each carrying `amount` (in cents), e.g.
        // [{"amount":200,"tax_rate":"txr_..."}]. There is no single `total_tax_amount` field.
        JsonObject taxItem = new JsonObject();
        taxItem.addProperty("amount", 200);
        taxItem.addProperty("tax_rate", "txr_123");
        com.google.gson.JsonArray taxArray = new com.google.gson.JsonArray();
        taxArray.add(taxItem);
        JsonObject amountDetails = new JsonObject();
        amountDetails.add("tax", taxArray);
        JsonObject object = new JsonObject();
        object.add("amount_details", amountDetails);
        JsonObject data = new JsonObject();
        data.add("object", object);
        JsonObject eventRaw = new JsonObject();
        eventRaw.add("data", data);

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .reservationId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .customerEmail("cust@example.com")
                .eventId(UUID.randomUUID())
                .amount(new BigDecimal("10.00"))
                .currency("USD")
                .stripePaymentIntentId("pi_123")
                .status(PaymentStatus.INITIATED)
                .build();
        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));

        Event event = paymentIntentEvent("payment_intent.succeeded", pi);
        when(event.getRawJsonObject()).thenReturn(eventRaw);

        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString())).thenReturn(event);
            service.handleWebhookEvent("{}", "sig");
        }

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        verify(paymentRepository).save(payment);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getEventType()).isEqualTo("PaymentCompleted");

        // 200 cents -> 2.00 tax; net = 10.00 - 2.00 = 8.00
        JsonNode payloadNode = outbox.getPayload();
        assertThat(payloadNode.get("payload").get("taxAmount").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(payloadNode.get("payload").get("netAmount").decimalValue()).isEqualByComparingTo("8.00");
    }

    @Test
    void duplicateSucceededIsSkippedIdempotently() throws Exception {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_123");

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .reservationId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .customerEmail("cust@example.com")
                .eventId(UUID.randomUUID())
                .amount(new BigDecimal("10.00"))
                .currency("USD")
                .stripePaymentIntentId("pi_123")
                .status(PaymentStatus.SUCCESS)
                .build();
        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));

        Event event = paymentIntentEvent("payment_intent.succeeded", pi);

        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString())).thenReturn(event);
            service.handleWebhookEvent("{}", "sig");
        }

        verify(paymentRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void failedTransitionCreatesPaymentFailedOutbox() throws Exception {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_123");
        StripeError lastError = mock(StripeError.class);
        when(pi.getLastPaymentError()).thenReturn(lastError);
        when(lastError.getMessage()).thenReturn("card declined");

        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .reservationId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .customerEmail("cust@example.com")
                .eventId(UUID.randomUUID())
                .amount(new BigDecimal("10.00"))
                .currency("USD")
                .stripePaymentIntentId("pi_123")
                .status(PaymentStatus.INITIATED)
                .build();
        when(paymentRepository.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.of(payment));

        Event event = paymentIntentEvent("payment_intent.payment_failed", pi);

        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString())).thenReturn(event);
            service.handleWebhookEvent("{}", "sig");
        }

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("card declined");
        verify(paymentRepository).save(payment);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PaymentFailed");
    }

    @Test
    void invalidSignatureThrowsValidationException() {
        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                    .thenThrow(new SignatureVerificationException("bad sig", "sig"));

            assertThatThrownBy(() -> service.handleWebhookEvent("{}", "sig"))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }
    }
}
