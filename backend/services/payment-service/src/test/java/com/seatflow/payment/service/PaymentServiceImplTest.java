package com.seatflow.payment.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.client.dto.SeatHoldClientDto;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.seatflow.payment.gateway.dto.StripeTaxResult;
import com.seatflow.payment.gateway.dto.TaxAddress;
import com.seatflow.payment.mapper.PaymentMapper;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.payment.repository.OutboxEventRepository;
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.request.TaxPreviewRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.TaxPreviewResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        service = new PaymentServiceImpl(paymentRepository, outboxEventRepository, objectMapper, reservationServiceClient,
                stripePaymentGateway, paymentMapper, meterRegistry, org.mockito.Mockito.mock(W3cTraceContextPropagator.class));
        when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentRepository.findByReservationId(any())).thenReturn(Optional.empty());
        when(stripePaymentGateway.createPaymentIntent(any(), any(), any(), any(), any()))
                .thenReturn(new StripeIntentResult("pi_123", "pi_123_secret", "requires_payment_method"));
        when(paymentMapper.toIntentResponse(any(Payment.class), any()))
                .thenReturn(new PaymentIntentResponse(UUID.randomUUID(), "pi_123_secret", amount, "USD", PaymentStatus.INITIATED));
        when(paymentMapper.toResponse(any(Payment.class)))
                .thenReturn(new com.seatflow.payment.web.dto.response.PaymentResponse(
                        UUID.randomUUID(), reservationId, reservationUserId, "cust@example.com",
                        reservationEventId, "pi_123", amount, BigDecimal.ZERO, amount, "USD", PaymentStatus.INITIATED, null,
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
    void createPaymentIntentUsesHeldSeatPricesWhenStoredReservationTotalIsInflated() {
        UUID seatId = UUID.randomUUID();
        ReservationClientResponse legacyReservation = new ReservationClientResponse(
                reservationId, reservationEventId, reservationUserId, "cust@example.com",
                "PENDING", Instant.now().plusSeconds(900), new BigDecimal("4040.00"), 1,
                List.of(new SeatHoldClientDto(UUID.randomUUID(), seatId, "HELD", new BigDecimal("20.00"))),
                Instant.now());
        when(reservationServiceClient.getReservation(reservationId)).thenReturn(legacyReservation);
        Payment saved = paymentWith(UUID.randomUUID(), PaymentStatus.INITIATED);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenReturn(saved);

        service.createPaymentIntent(request(), reservationUserId);

        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stripePaymentGateway).createPaymentIntent(
                amountCaptor.capture(), any(), any(), any(), any());
        assertThat(amountCaptor.getValue()).isEqualByComparingTo("20.00");

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void calculateTaxPreviewUsesThePaymentAmountAndBillingAddress() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = paymentWith(paymentId, PaymentStatus.INITIATED);
        payment.setUserId(reservationUserId);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCurrency("USD");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(stripePaymentGateway.calculateInclusiveTax(
                eq(new BigDecimal("100.00")), eq("USD"), eq(paymentId.toString()), any(TaxAddress.class)))
                .thenReturn(new StripeTaxResult(new BigDecimal("19.00"), new BigDecimal("23.46"), "USD"));

        TaxPreviewResponse response = service.calculateTaxPreview(
                paymentId,
                new TaxPreviewRequest("1 Test Avenue", null, "Bucharest", null, "010101", "RO"),
                reservationUserId,
                false);

        assertThat(response.taxAmount()).isEqualByComparingTo("19.00");
        assertThat(response.effectiveRate()).isEqualByComparingTo("23.46");
        assertThat(response.currency()).isEqualTo("USD");
        verify(stripePaymentGateway).calculateInclusiveTax(
                eq(new BigDecimal("100.00")), eq("USD"), eq(paymentId.toString()), any(TaxAddress.class));
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
    void idempotentReplayRefreshesStripeIntentWhenTicketPricingChanged() {
        UUID paymentId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Payment existing = Payment.builder()
                .id(paymentId)
                .reservationId(reservationId)
                .stripePaymentIntentId("pi_existing")
                .clientSecret("pi_existing_secret")
                .amount(new BigDecimal("120.00"))
                .currency("USD")
                .status(PaymentStatus.INITIATED)
                .build();
        ReservationClientResponse repricedReservation = new ReservationClientResponse(
                reservationId, reservationEventId, reservationUserId, "cust@example.com",
                "PENDING", Instant.now().plusSeconds(900), new BigDecimal("100.00"), 1,
                List.of(new SeatHoldClientDto(UUID.randomUUID(), seatId, "HELD", new BigDecimal("100.00"))),
                Instant.now());
        when(paymentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));
        when(reservationServiceClient.getReservation(reservationId)).thenReturn(repricedReservation);
        when(stripePaymentGateway.updatePaymentIntent(
                eq("pi_existing"), eq(new BigDecimal("100.00")), eq("USD"), any(), eq("cust@example.com")))
                .thenReturn(new StripeIntentResult("pi_existing", "pi_updated_secret", "requires_payment_method"));

        service.createPaymentIntent(request(), reservationUserId);

        assertThat(existing.getAmount()).isEqualByComparingTo("100.00");
        verify(stripePaymentGateway).updatePaymentIntent(
                eq("pi_existing"), eq(new BigDecimal("100.00")), eq("USD"), any(), eq("cust@example.com"));
        verify(paymentRepository).saveAndFlush(existing);
        verify(paymentMapper).toIntentResponse(existing, "pi_updated_secret");
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

    @Test
    void guestPaymentRejectsAnonymousCallerWithoutEmailProof() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .reservationId(reservationId)
                .customerEmail("guest@example.com")
                .status(PaymentStatus.INITIATED)
                .build();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.getPaymentById(paymentId, null, false, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void guestPaymentAcceptsAnonymousCallerWithMatchingEmailProof() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .reservationId(reservationId)
                .customerEmail("guest@example.com")
                .status(PaymentStatus.INITIATED)
                .build();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        assertThat(service.getPaymentById(paymentId, null, false, " Guest@Example.com ")).isNotNull();
    }

    @Test
    void guestPaymentRejectsAnonymousCallerWithWrongEmailProof() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.builder()
                .id(paymentId)
                .reservationId(reservationId)
                .customerEmail("guest@example.com")
                .status(PaymentStatus.INITIATED)
                .build();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.getPaymentById(paymentId, null, false, "attacker@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void claimGuestPaymentsUpdatesPaymentsForCustomerEmail() {
        UUID userId = UUID.randomUUID();
        String customerEmail = "guest@example.com";
        when(paymentRepository.updateUserIdForCustomerEmail(eq(userId), eq(customerEmail), any(Instant.class)))
                .thenReturn(3);

        int updated = service.claimGuestPayments(userId, customerEmail);

        assertThat(updated).isEqualTo(3);
        verify(paymentRepository, times(1)).updateUserIdForCustomerEmail(eq(userId), eq(customerEmail), any(Instant.class));
    }

    @Test
    void claimGuestPaymentsReturnsZeroWhenUserIdOrEmailIsInvalid() {
        assertThat(service.claimGuestPayments(null, "guest@example.com")).isEqualTo(0);
        assertThat(service.claimGuestPayments(UUID.randomUUID(), null)).isEqualTo(0);
        assertThat(service.claimGuestPayments(UUID.randomUUID(), "   ")).isEqualTo(0);
        verify(paymentRepository, never()).updateUserIdForCustomerEmail(any(), any(), any());
    }
}
