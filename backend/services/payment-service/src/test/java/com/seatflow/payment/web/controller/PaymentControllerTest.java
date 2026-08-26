package com.seatflow.payment.web.controller;

import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import com.seatflow.common.security.converter.JwtRoleConverter;
import com.seatflow.payment.config.SecurityConfig;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.service.PaymentService;
import com.seatflow.payment.service.StripeWebhookService;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private StripeWebhookService stripeWebhookService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private JwtRoleConverter jwtRoleConverter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createPaymentIntentReturns201Created() throws Exception {
        CreatePaymentIntentRequest request = new CreatePaymentIntentRequest(UUID.randomUUID(), "idem-key-1");
        PaymentIntentResponse response = new PaymentIntentResponse(
                UUID.randomUUID(), "pi_secret_123", new BigDecimal("49.99"), "USD", PaymentStatus.INITIATED);
        when(paymentService.createPaymentIntent(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/payments/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientSecret").value("pi_secret_123"));
    }

    @Test
    void createPaymentIntentReturns400WhenReservationIdMissing() throws Exception {
        String body = "{\"idempotencyKey\":\"idem-key-1\"}";

        mockMvc.perform(post("/api/payments/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPaymentReturns200Ok() throws Exception {
        PaymentResponse response = new PaymentResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "cust@example.com",
                UUID.randomUUID(), "pi_123", new BigDecimal("49.99"), "USD", PaymentStatus.INITIATED,
                null, Instant.now(), Instant.now());
        when(paymentService.getPaymentById(any(), any(), anyBoolean())).thenReturn(response);

        mockMvc.perform(get("/api/payments/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INITIATED"));
    }

    @Test
    void getPaymentByReservationReturns200Ok() throws Exception {
        PaymentResponse response = new PaymentResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "cust@example.com",
                UUID.randomUUID(), "pi_123", new BigDecimal("49.99"), "USD", PaymentStatus.INITIATED,
                null, Instant.now(), Instant.now());
        when(paymentService.getPaymentByReservationId(any(), any(), anyBoolean())).thenReturn(response);

        mockMvc.perform(get("/api/payments/reservation/" + UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    @Test
    void webhookWithValidSignatureCallsServiceAndReturns200() throws Exception {
        String payload = "{\"id\":\"evt_1\"}";
        String sig = "t=123,v1=signature";

        mockMvc.perform(post("/api/payments/webhook")
                        .header("Stripe-Signature", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(stripeWebhookService).handleWebhookEvent(payload, sig);
    }

    @Test
    void webhookWithoutSignatureReturns400() throws Exception {
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
