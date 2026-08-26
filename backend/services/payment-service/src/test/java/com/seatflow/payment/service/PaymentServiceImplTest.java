package com.seatflow.payment.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.seatflow.payment.mapper.PaymentMapper;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ReservationServiceClient reservationServiceClient;

    @Mock
    private StripePaymentGateway stripePaymentGateway;

    @Mock
    private PaymentMapper paymentMapper;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PaymentServiceImpl service;

    private final UUID reservationId = UUID.randomUUID();
    private final UUID reservationEventId = UUID.randomUUID();
    private final UUID reservationUserId = UUID.randomUUID();
    private final String idempotencyKey = "idem-key-001";
    private final BigDecimal amount = new BigDecimal("120.00");

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(paymentRepository, reservationServiceClient, stripePaymentGateway, paymentMapper, meterRegistry);
        when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentRepository.findByReservationId(any())).thenReturn(Optional.empty());
        when(stripePaymentGateway.createPaymentIntent(any(), any(), any(), any(), any()))
                .thenReturn(new StripeIntentResult("pi_123", "pi_123_secret", "requires_payment_method"));
        when(paymentMapper.toIntentResponse(any(Payment.class), any()))
                .thenReturn(new PaymentIntentResponse(UUID.randomUUID(), "pi_123_secret", amount, "USD", PaymentStatus.INITIATED));
        when(paymentMapper.toResponse(any(Payment.class)))
                .thenReturn(new com.seatflow.payment.web.dto.response.PaymentResponse(
                        UUID.randomUUID(), reservationId, reservationUserId, "cust@example.com",
                        reservationEventId, "pi_123", amount, "USD", PaymentStatus.INITIATED, null,
                        Instant.now(), Instant.now()));
    }

    private CreatePaymentIntentRequest request() {
        return new CreatePaymentIntentRequest(reservationId, idempotencyKey);
    }

    private ReservationClientResponse pendingReservation(UUID userId) {
        return new ReservationClientResponse(
                reservationId, reservationEventId, userId, "cust@example.com",
                "PENDING", Instant.now().plusSeconds(900), amount, 2, List.of(), Instant.now());
    }

    private Payment paymentWith(UUID id, PaymentStatus status) {
        return Payment.builder()
                .id(id)
                .reservationId(reservationId)
                .status(status)
                .build();
    }

    @Test
    void createPaymentIntentPersistsInitiatedPaymentAndReturnsClientSecret() {
        when(reservationServiceClient.getReservation(reservationId)).thenReturn(pendingReservation(reservationUserId));
        Payment saved = paymentWith(UUID.randomUUID(), PaymentStatus.INITIATED);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenReturn(saved);

        PaymentIntentResponse response = service.createPaymentIntent(request(), reservationUserId);

        assertThat(response.status()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(response.clientSecret()).isEqualTo("pi_123_secret");
        verify(stripePaymentGateway, times(1)).createPaymentIntent(any(), any(), any(), any(), any());
        verify(paymentRepository, times(1)).saveAndFlush(any(Payment.class));
    }

    @Test
    void createPaymentIntentRejectsExpiredReservation() {
        ReservationClientResponse expired = new ReservationClientResponse(
                reservationId, reservationEventId, reservationUserId, "cust@example.com",
                "PENDING", Instant.now().minusSeconds(60), amount, 2, List.of(), Instant.now());
        when(reservationServiceClient.getReservation(reservationId)).thenReturn(expired);

        assertThatThrownBy(() -> service.createPaymentIntent(request(), reservationUserId))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.RESERVATION_EXPIRED));
        verify(stripePaymentGateway, never()).createPaymentIntent(any(), any(), any(), any(), any());
    }

    @Test
    void createPaymentIntentRejectsNonPendingReservation() {
        ReservationClientResponse confirmed = new ReservationClientResponse(
                reservationId, reservationEventId, reservationUserId, "cust@example.com",
                "CONFIRMED", Instant.now().plusSeconds(900), amount, 2, List.of(), Instant.now());
        when(reservationServiceClient.getReservation(reservationId)).thenReturn(confirmed);

        assertThatThrownBy(() -> service.createPaymentIntent(request(), reservationUserId))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void createPaymentIntentRejectsAlreadyPaidReservation() {
        when(paymentRepository.findByReservationId(reservationId))
                .thenReturn(Optional.of(paymentWith(UUID.randomUUID(), PaymentStatus.SUCCESS)));

        assertThatThrownBy(() -> service.createPaymentIntent(request(), reservationUserId))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED));
        verify(reservationServiceClient, never()).getReservation(any());
    }

    @Test
    void createPaymentIntentReplaysIdempotentRequestWithMatchingReservation() {
        Payment existing = paymentWith(UUID.randomUUID(), PaymentStatus.INITIATED);
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        PaymentIntentResponse response = service.createPaymentIntent(request(), reservationUserId);

        assertThat(response.clientSecret()).isEqualTo("pi_123_secret");
        verify(stripePaymentGateway, never()).createPaymentIntent(any(), any(), any(), any(), any());
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
    }

    @Test
    void createPaymentIntentRejectsIdempotencyKeyReusedWithDifferentReservation() {
        Payment existing = Payment.builder()
                .id(UUID.randomUUID())
                .reservationId(UUID.randomUUID())
                .status(PaymentStatus.INITIATED)
                .build();
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createPaymentIntent(request(), reservationUserId))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void createPaymentIntentRejectsUnauthorizedOwnerForRegisteredReservation() {
        when(reservationServiceClient.getReservation(reservationId)).thenReturn(pendingReservation(reservationUserId));

        UUID otherUser = UUID.randomUUID();
        assertThatThrownBy(() -> service.createPaymentIntent(request(), otherUser))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(stripePaymentGateway, never()).createPaymentIntent(any(), any(), any(), any(), any());
    }

    @Test
    void createPaymentIntentRejectsUnauthenticatedCallerForRegisteredReservation() {
        when(reservationServiceClient.getReservation(reservationId)).thenReturn(pendingReservation(reservationUserId));

        assertThatThrownBy(() -> service.createPaymentIntent(request(), null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(stripePaymentGateway, never()).createPaymentIntent(any(), any(), any(), any(), any());
    }

    @Test
    void getPaymentByReservationIdEnforcesOwnership() {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .userId(reservationUserId)
                .status(PaymentStatus.INITIATED)
                .build();
        when(paymentRepository.findByReservationId(reservationId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.getPaymentByReservationId(reservationId, UUID.randomUUID(), false))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(service.getPaymentByReservationId(reservationId, reservationUserId, false)).isNotNull();
    }
}
