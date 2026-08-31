package com.seatflow.payment.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
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
import com.seatflow.payment.repository.PaymentRepository;
import com.seatflow.payment.service.PaymentService;
import com.seatflow.payment.web.dto.request.CreatePaymentIntentRequest;
import com.seatflow.payment.web.dto.request.TaxPreviewRequest;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import com.seatflow.payment.web.dto.response.TaxPreviewResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.payment.messaging.event.PaymentCompletedEvent;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final ReservationServiceClient reservationServiceClient;
    private final StripePaymentGateway stripePaymentGateway;
    private final PaymentMapper paymentMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public PaymentIntentResponse createPaymentIntent(CreatePaymentIntentRequest request, UUID authenticatedUserId) {
        return createPaymentIntent(request, authenticatedUserId, null);
    }

    @Override
    public PaymentIntentResponse createPaymentIntent(CreatePaymentIntentRequest request,
                                                     UUID authenticatedUserId,
                                                     String customerEmailProof) {
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            log.info("Processing PaymentIntent creation. reservationId={}, authenticatedUserId={}",
                    request.reservationId(), authenticatedUserId);

            // 1. Check idempotency on client idempotency key
            Optional<Payment> existingIdempotentPayment = paymentRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existingIdempotentPayment.isPresent()) {
                Payment existing = existingIdempotentPayment.get();
                if (existing.getReservationId().equals(request.reservationId())) {
                    refreshExistingPaymentIfReservationTotalChanged(existing, authenticatedUserId, customerEmailProof);
                    log.info("Idempotent payment replay. paymentId={}, reservationId={}",
                            existing.getId(), existing.getReservationId());
                    return paymentMapper.toIntentResponse(existing, existing.getClientSecret());
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
            ReservationClientResponse reservation = getReservation(request.reservationId(), customerEmailProof);

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

            // Seat prices are the line-item source of truth. Recalculate the amount from the
            // held seats so legacy reservations created before the seat-map filtering fix cannot
            // charge the price of every seat in the venue.
            BigDecimal chargeAmount = resolveChargeAmount(reservation);

            // 5. Create Stripe PaymentIntent via Gateway
            Map<String, String> metadata = new HashMap<>();
            metadata.put("reservationId", reservation.id().toString());
            metadata.put("eventId", reservation.eventId().toString());
            metadata.put("customerEmail", reservation.customerEmail());
            if (reservation.userId() != null) {
                metadata.put("userId", reservation.userId().toString());
            }

            StripeIntentResult stripeResult = stripePaymentGateway.createPaymentIntent(
                    chargeAmount,
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
                    .clientSecret(stripeResult.clientSecret())
                    .idempotencyKey(request.idempotencyKey())
                    .amount(chargeAmount)
                    .currency("USD")
                    .status(PaymentStatus.INITIATED)
                    .build();

            Payment savedPayment = persistPayment(payment);

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

    @Transactional
    private Payment persistPayment(Payment payment) {
        return paymentRepository.saveAndFlush(payment);
    }

    private void refreshExistingPaymentIfReservationTotalChanged(Payment payment,
                                                                  UUID authenticatedUserId,
                                                                  String customerEmailProof) {
        // Older persisted test fixtures may not have Stripe identifiers. Keep their
        // idempotent replay behavior intact; real payment rows always have both values.
        if (payment.getStripePaymentIntentId() == null || payment.getAmount() == null) {
            return;
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return;
        }

        ReservationClientResponse reservation = getReservation(payment.getReservationId(), customerEmailProof);
        if (!"PENDING".equalsIgnoreCase(reservation.status())) {
            throw new ValidationException("Cannot refresh payment for reservation with status: " + reservation.status(),
                    ErrorCode.INVALID_REQUEST);
        }
        if (reservation.expiresAt() == null || reservation.expiresAt().isBefore(Instant.now())) {
            throw new ValidationException("Reservation hold has expired", ErrorCode.RESERVATION_EXPIRED);
        }
        if (reservation.userId() != null
                && (authenticatedUserId == null || !reservation.userId().equals(authenticatedUserId))
                && !UserContext.hasRole(SecurityRoles.ROLE_ADMIN)) {
            throw new ResourceNotFoundException("Reservation", payment.getReservationId());
        }

        BigDecimal chargeAmount = resolveChargeAmount(reservation);
        if (payment.getAmount().compareTo(chargeAmount) == 0) {
            return;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("reservationId", reservation.id().toString());
        metadata.put("eventId", reservation.eventId().toString());
        metadata.put("customerEmail", reservation.customerEmail());
        if (reservation.userId() != null) {
            metadata.put("userId", reservation.userId().toString());
        }

        StripeIntentResult stripeResult = stripePaymentGateway.updatePaymentIntent(
                payment.getStripePaymentIntentId(),
                chargeAmount,
                payment.getCurrency(),
                metadata,
                reservation.customerEmail());
        payment.setAmount(chargeAmount);
        if (stripeResult.clientSecret() != null && !stripeResult.clientSecret().isBlank()) {
            payment.setClientSecret(stripeResult.clientSecret());
        }
        persistPayment(payment);
        log.info("Payment amount refreshed after reservation pricing update. paymentId={}, amount={}",
                payment.getId(), chargeAmount);
    }

    private BigDecimal resolveChargeAmount(ReservationClientResponse reservation) {
        List<SeatHoldClientDto> seats = reservation.seats();
        if (seats != null && !seats.isEmpty()) {
            BigDecimal seatTotal = seats.stream()
                    .map(SeatHoldClientDto::price)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (seatTotal.signum() <= 0) {
                throw new ValidationException("Reservation does not contain valid seat prices", ErrorCode.INVALID_REQUEST);
            }
            if (reservation.totalAmount() == null || reservation.totalAmount().compareTo(seatTotal) != 0) {
                log.warn("Correcting reservation total from seat line items. reservationId={}, storedTotal={}, seatTotal={}",
                        reservation.id(), reservation.totalAmount(), seatTotal);
            }
            return seatTotal;
        }

        if (reservation.totalAmount() == null || reservation.totalAmount().signum() <= 0) {
            throw new ValidationException("Reservation total must be positive", ErrorCode.INVALID_REQUEST);
        }
        return reservation.totalAmount();
    }

    private ReservationClientResponse getReservation(UUID reservationId, String customerEmailProof) {
        if (customerEmailProof == null || customerEmailProof.isBlank()) {
            return reservationServiceClient.getReservation(reservationId);
        }
        return reservationServiceClient.getReservation(reservationId, customerEmailProof.trim());
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPaymentById(UUID paymentId, UUID authenticatedUserId, boolean isAdmin) {
        return getPaymentById(paymentId, authenticatedUserId, isAdmin, null);
    }

    @Override
    @Transactional
    public PaymentResponse getPaymentById(UUID paymentId,
                                          UUID authenticatedUserId,
                                          boolean isAdmin,
                                          String customerEmailProof) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        assertAuthorized(payment, authenticatedUserId, isAdmin, customerEmailProof);
        payment = syncPaymentStatusIfSucceeded(payment);
        return paymentMapper.toResponse(payment);
    }

    @Override
    public TaxPreviewResponse calculateTaxPreview(UUID paymentId,
                                                   TaxPreviewRequest request,
                                                   UUID authenticatedUserId,
                                                   boolean isAdmin) {
        return calculateTaxPreview(paymentId, request, authenticatedUserId, isAdmin, null);
    }

    @Override
    public TaxPreviewResponse calculateTaxPreview(UUID paymentId,
                                                   TaxPreviewRequest request,
                                                   UUID authenticatedUserId,
                                                   boolean isAdmin,
                                                   String customerEmailProof) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        assertAuthorized(payment, authenticatedUserId, isAdmin, customerEmailProof);
        StripeTaxResult result = stripePaymentGateway.calculateInclusiveTax(
                payment.getAmount(),
                payment.getCurrency(),
                paymentId.toString(),
                new TaxAddress(request.line1(), request.line2(), request.city(), request.state(),
                        request.postalCode(), request.country()));

        if (result != null && result.taxAmount() != null) {
            payment.setTaxAmount(result.taxAmount());
            payment.setNetAmount(payment.getAmount().subtract(result.taxAmount()));
            paymentRepository.save(payment);
        }

        return new TaxPreviewResponse(result.taxAmount(), result.effectiveRate(), result.currency());
    }

    @Override
    @Transactional
    public PaymentResponse getPaymentByReservationId(UUID reservationId, UUID authenticatedUserId, boolean isAdmin) {
        return getPaymentByReservationId(reservationId, authenticatedUserId, isAdmin, null);
    }

    @Override
    @Transactional
    public PaymentResponse getPaymentByReservationId(UUID reservationId,
                                                     UUID authenticatedUserId,
                                                     boolean isAdmin,
                                                     String customerEmailProof) {
        Payment payment = paymentRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment for reservation", reservationId));

        assertAuthorized(payment, authenticatedUserId, isAdmin, customerEmailProof);
        payment = syncPaymentStatusIfSucceeded(payment);
        return paymentMapper.toResponse(payment);
    }

    private Payment syncPaymentStatusIfSucceeded(Payment payment) {
        if (payment.getStatus() == PaymentStatus.INITIATED && payment.getStripePaymentIntentId() != null) {
            try {
                com.stripe.model.PaymentIntent intent = stripePaymentGateway.retrievePaymentIntent(payment.getStripePaymentIntentId());
                if (intent != null && "succeeded".equalsIgnoreCase(intent.getStatus())) {
                    log.info("Synchronizing succeeded payment from Stripe gateway. paymentId={}, stripePaymentIntentId={}",
                            payment.getId(), payment.getStripePaymentIntentId());

                    BigDecimal taxAmount = payment.getTaxAmount() != null ? payment.getTaxAmount() : BigDecimal.ZERO;
                    BigDecimal netAmount = payment.getAmount().subtract(taxAmount);

                    payment.setStatus(PaymentStatus.SUCCESS);
                    payment.setTaxAmount(taxAmount);
                    payment.setNetAmount(netAmount);
                    payment.setUpdatedAt(Instant.now());
                    payment = paymentRepository.saveAndFlush(payment);

                    PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                            payment.getId(),
                            payment.getReservationId(),
                            payment.getUserId(),
                            payment.getCustomerEmail(),
                            payment.getEventId(),
                            payment.getAmount(),
                            taxAmount,
                            netAmount,
                            payment.getCurrency(),
                            payment.getStripePaymentIntentId(),
                            Instant.now()
                    );

                    saveOutboxRecord("PaymentCompleted", payment.getId(), completedEvent);
                    meterRegistry.counter("seatflow.payments.completed.total", "status", "SUCCESS").increment();
                }
            } catch (Exception ex) {
                log.warn("Unable to sync payment status with Stripe for paymentId={}: {}", payment.getId(), ex.getMessage());
            }
        }
        return payment;
    }

    private void saveOutboxRecord(String eventType, UUID aggregateId, Object payload) {
        try {
            EventEnvelope<?> envelope = EventEnvelope.of(
                    eventType,
                    aggregateId.toString(),
                    CorrelationContext.getCorrelationId().orElse(UUID.randomUUID().toString()),
                    (com.seatflow.common.events.DomainEvent) payload
            );
            JsonNode payloadNode = objectMapper.valueToTree(envelope);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payloadNode)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (Exception ex) {
            log.error("Failed to save outbox event for eventType={}, aggregateId={}", eventType, aggregateId, ex);
        }
    }

    private void assertAuthorized(Payment payment,
                                  UUID authenticatedUserId,
                                  boolean isAdmin,
                                  String customerEmailProof) {
        if (isAdmin) {
            return;
        }
        if (payment.getUserId() != null) {
            // Registered payment: only the owner may access it.
            if (authenticatedUserId == null || !payment.getUserId().equals(authenticatedUserId)) {
                throw new ResourceNotFoundException("Payment", payment.getId());
            }
            return;
        }
        // Guest payment: require proof of the email used during checkout. An authenticated
        // caller whose verified JWT email matches the payment also qualifies (ADR-001).
        String callerEmail = UserContext.getCurrentUserEmail().orElse(null);
        boolean jwtEmailMatches = callerEmail != null
                && callerEmail.equalsIgnoreCase(payment.getCustomerEmail());
        boolean proofMatches = customerEmailProof != null
                && customerEmailProof.trim().equalsIgnoreCase(payment.getCustomerEmail());
        if (!jwtEmailMatches && !proofMatches) {
            throw new ResourceNotFoundException("Payment", payment.getId());
        }
    }

    @Override
    @Transactional
    public int claimGuestPayments(UUID userId, String customerEmail) {
        if (userId == null || customerEmail == null || customerEmail.isBlank()) {
            log.warn("Cannot claim guest payments with null userId or blank email. userId={}, email={}", userId, customerEmail);
            return 0;
        }
        log.info("Claiming historical guest payments for newly registered user. userId={}, email={}", userId, customerEmail);
        int updatedCount = paymentRepository.updateUserIdForCustomerEmail(userId, customerEmail, Instant.now());
        log.info("Claimed historical guest payments. userId={}, email={}, count={}", userId, customerEmail, updatedCount);
        return updatedCount;
    }
}
