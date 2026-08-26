package com.seatflow.payment.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.payment.client.ReservationServiceClient;
import com.seatflow.payment.client.dto.ReservationClientResponse;
import com.seatflow.payment.gateway.StripePaymentGateway;
import com.seatflow.payment.gateway.dto.StripeIntentResult;
import com.seatflow.payment.mapper.PaymentMapper;
import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.model.enums.PaymentStatus;
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.service.PaymentService;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationServiceClient reservationServiceClient;
    private final StripePaymentGateway stripePaymentGateway;
    private final PaymentMapper paymentMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public PaymentIntentResponse createPaymentIntent(CreatePaymentIntentRequest request, UUID authenticatedUserId) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            log.info("Processing PaymentIntent creation. reservationId={}, authenticatedUserId={}, idempotencyKey={}",
                    request.reservationId(), authenticatedUserId, request.idempotencyKey());

            // 1. Check idempotency on client idempotency key
            Optional<Payment> existingIdempotentPayment = paymentRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existingIdempotentPayment.isPresent()) {
                Payment existing = existingIdempotentPayment.get();
                if (existing.getReservationId().equals(request.reservationId())) {
                    log.info("Idempotent replay for paymentId={}, idempotencyKey={}", existing.getId(), request.idempotencyKey());
                    return paymentMapper.toIntentResponse(existing, null);
                } else {
                    throw new ConflictException("Idempotency key reused with different parameters", ErrorCode.CONFLICT);
                }
            }

            // 2. Check if a payment pipeline already exists for this reservationId
            Optional<Payment> existingResPayment = paymentRepository.findByReservationId(request.reservationId());
            if (existingResPayment.isPresent()) {
                Payment payment = existingResPayment.get();
                if (payment.getStatus() == PaymentStatus.SUCCESS) {
                    throw new ConflictException("Reservation is already paid", ErrorCode.PAYMENT_ALREADY_PROCESSED);
                }
            }

            // 3. Fetch and validate reservation from reservation-service
            ReservationClientResponse reservation = reservationServiceClient.getReservation(request.reservationId());

            if (!"PENDING".equalsIgnoreCase(reservation.status())) {
                throw new ValidationException("Cannot process payment for reservation with status: " + reservation.status(),
                        ErrorCode.INVALID_REQUEST);
            }

            if (reservation.expiresAt() == null || reservation.expiresAt().isBefore(Instant.now())) {
                throw new ValidationException("Reservation hold has expired", ErrorCode.RESERVATION_EXPIRED);
            }

            // 4. Authorization check for registered users
            if (reservation.userId() != null) {
                boolean isOwner = authenticatedUserId != null && reservation.userId().equals(authenticatedUserId);
                if (!isOwner && !UserContext.hasRole(SecurityRoles.ROLE_ADMIN)) {
                    throw new ResourceNotFoundException("Reservation", request.reservationId());
                }
            }

            // 5. Create Stripe PaymentIntent via Gateway
            Map<String, String> metadata = new HashMap<>();
            metadata.put("reservationId", reservation.id().toString());
            metadata.put("eventId", reservation.eventId().toString());
            metadata.put("customerEmail", reservation.customerEmail());
            if (reservation.userId() != null) {
                metadata.put("userId", reservation.userId().toString());
            }

            StripeIntentResult stripeResult = stripePaymentGateway.createPaymentIntent(
                    reservation.totalAmount(),
                    "USD",
                    request.idempotencyKey(),
                    metadata,
                    reservation.customerEmail()
            );

            // 6. Persist Payment entity in INITIATED status
            Payment payment = Payment.builder()
                    .reservationId(reservation.id())
                    .userId(reservation.userId() != null ? reservation.userId() : authenticatedUserId)
                    .customerEmail(reservation.customerEmail())
                    .eventId(reservation.eventId())
                    .stripePaymentIntentId(stripeResult.paymentIntentId())
                    .idempotencyKey(request.idempotencyKey())
                    .amount(reservation.totalAmount())
                    .currency("USD")
                    .status(PaymentStatus.INITIATED)
                    .build();

            Payment savedPayment = paymentRepository.saveAndFlush(payment);

            meterRegistry.counter("seatflow.payments.intent.created.total", "status", "INITIATED").increment();
            log.info("Payment entity persisted in INITIATED status. paymentId={}, stripePaymentIntentId={}, amount={}",
                    savedPayment.getId(), savedPayment.getStripePaymentIntentId(), savedPayment.getAmount());

            return paymentMapper.toIntentResponse(savedPayment, stripeResult.clientSecret());

        } catch (DataIntegrityViolationException dive) {
            log.warn("Database integrity violation during payment intent creation. reservationId={}", request.reservationId(), dive);
            meterRegistry.counter("seatflow.payments.conflicts.total", "reason", "DB_UNIQUE_VIOLATION").increment();
            throw new ConflictException("Payment pipeline already initiated for this reservation", ErrorCode.CONFLICT);
        } finally {
            timer.stop(meterRegistry.timer("seatflow.payments.intent.duration"));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId, UUID authenticatedUserId, boolean isAdmin) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        if (!isAdmin && payment.getUserId() != null) {
            if (authenticatedUserId == null || !payment.getUserId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Payment", paymentId);
            }
        }

        return paymentMapper.toResponse(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByReservationId(UUID reservationId, UUID authenticatedUserId, boolean isAdmin) {
        Payment payment = paymentRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment for reservation", reservationId));

        if (!isAdmin && payment.getUserId() != null) {
            if (authenticatedUserId == null || !payment.getUserId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Payment for reservation", reservationId);
            }
        }

        return paymentMapper.toResponse(payment);
    }
}
