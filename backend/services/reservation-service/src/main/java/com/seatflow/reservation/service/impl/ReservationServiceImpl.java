package com.seatflow.reservation.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.mapper.ReservationMapper;
import com.seatflow.reservation.messaging.event.PaymentCompletedEvent;
import com.seatflow.reservation.messaging.event.ReservationCancelledEvent;
import com.seatflow.reservation.messaging.event.ReservationHeldEvent;
import com.seatflow.reservation.messaging.event.UserRegisteredEvent;
import com.seatflow.reservation.model.entity.OutboxEvent;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.repository.projection.ActiveSeatHoldProjection;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.EventSeatStatusResponse;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatAvailabilityResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceImpl implements ReservationService {

    private static final int MAX_SEATS_PER_RESERVATION = 10;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(15);

    private final ReservationRepository reservationRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ReservationMapper reservationMapper;
    private final EventClient eventClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request, UUID authenticatedUserId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Instant startTime = Instant.now();
        try {
            if (request.seatIds().size() > MAX_SEATS_PER_RESERVATION) {
                throw new ValidationException("Cannot reserve more than 10 seats per reservation", ErrorCode.MAX_SEATS_EXCEEDED);
            }

            UUID userId = authenticatedUserId;
            String customerEmail = resolveCustomerEmail(request);
            Objects.requireNonNull(customerEmail, "Customer email is required to create a reservation");

            EventPricingDetails eventPricing = eventClient.getEventSeatPricing(
                    request.eventId(), new HashSet<>(request.seatIds()));

            BigDecimal authoritativeTotal = eventPricing.seatPrices().values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (request.seatPrices() != null) {
                BigDecimal requestedTotal = request.seatPrices().stream()
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (requestedTotal.compareTo(authoritativeTotal) != 0) {
                    throw new ValidationException("Client price does not match server computed price", ErrorCode.INVALID_REQUEST);
                }
            }

            Optional<Reservation> existing = reservationRepository.findWithSeatHoldsByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                Reservation prior = existing.get();
                Set<UUID> priorSeats = prior.getSeatHolds().stream()
                        .map(SeatHold::getSeatId)
                        .collect(Collectors.toSet());
                if (priorSeats.equals(new HashSet<>(request.seatIds()))) {
                    log.info("Idempotent replay for idempotencyKey={}, reservationId={}",
                            request.idempotencyKey(), prior.getId());
                    return reservationMapper.toResponse(prior);
                }
                throw new ConflictException("Idempotency key reused with different seats", ErrorCode.CONFLICT);
            }

            List<SeatHold> conflicting = seatHoldRepository.findByEventIdAndSeatIdInAndStatusIn(
                    request.eventId(), request.seatIds(), List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD));
            if (!conflicting.isEmpty()) {
                List<UUID> conflictingSeatIds = conflicting.stream().map(SeatHold::getSeatId).toList();
                log.warn("Seat hold conflict eventId={}, conflictingSeats={}", request.eventId(), conflictingSeatIds);
                throw new ConflictException("One or more seats are already held or sold", ErrorCode.SEAT_ALREADY_RESERVED);
            }

            Reservation reservation = reservationMapper.toEntity(request, userId);
            reservation.setCustomerEmail(customerEmail);
            reservation.setTotalAmount(authoritativeTotal);

            Instant now = Instant.now();
            Instant expiresAt = now.plus(HOLD_DURATION);
            reservation.setExpiresAt(expiresAt);

            Map<UUID, BigDecimal> priceMap = eventPricing.seatPrices();
            for (UUID seatId : request.seatIds()) {
                SeatHold hold = SeatHold.builder()
                        .eventId(request.eventId())
                        .seatId(seatId)
                        .status(SeatHoldStatus.HELD)
                        .price(priceMap.getOrDefault(seatId, BigDecimal.ZERO))
                        .build();
                reservation.addSeatHold(hold);
            }

            Reservation saved;
            try {
                saved = reservationRepository.saveAndFlush(reservation);
            } catch (DataIntegrityViolationException ex) {
                log.warn("Concurrent seat hold detected eventId={}, seats={}", request.eventId(), request.seatIds(), ex);
                throw new ConflictException("One or more seats were taken concurrently", ErrorCode.SEAT_ALREADY_RESERVED);
            }

            saveOutboxRecord("ReservationHeldEvent", saved.getId(), new ReservationHeldEvent(
                    saved.getId(),
                    saved.getEventId(),
                    saved.getUserId(),
                    saved.getCustomerEmail(),
                    new ArrayList<>(request.seatIds()),
                    saved.getExpiresAt(),
                    saved.getTotalAmount(),
                    now));

            meterRegistry.counter("seatflow.reservations.created.total",
                    "eventId", request.eventId().toString(),
                    "status", "SUCCESS").increment();

            long durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();
            log.info("Reservation hold created reservationId={}, eventId={}, userId={}, seatsCount={}, seatIds={}, totalAmount={}, expiresAt={}, durationMs={}",
                    saved.getId(), saved.getEventId(), userId, request.seatIds().size(), request.seatIds(),
                    saved.getTotalAmount(), saved.getExpiresAt(), durationMs);

            return reservationMapper.toResponse(saved);
        } catch (ConflictException ex) {
            meterRegistry.counter("seatflow.reservations.conflicts.total",
                    "eventId", request.eventId().toString(),
                    "reason", ex.getErrorCode().name()).increment();
            throw ex;
        } catch (ValidationException ex) {
            meterRegistry.counter("seatflow.reservations.validation.total",
                    "eventId", request.eventId().toString(),
                    "reason", ex.getErrorCode().name()).increment();
            throw ex;
        } finally {
            sample.stop(meterRegistry.timer("seatflow.reservations.hold.duration",
                    "eventId", request.eventId().toString()));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getReservationById(UUID reservationId, UUID authenticatedUserId) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (!isOwnerOrAdmin(reservation, authenticatedUserId)) {
            throw new ResourceNotFoundException("Reservation", reservationId);
        }

        return reservationMapper.toResponse(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public SeatAvailabilityResponse getSeatAvailability(UUID eventId) {
        List<ActiveSeatHoldProjection> holds = seatHoldRepository.findActiveSeatHoldsByEventId(eventId);
        List<EventSeatStatusResponse> statuses = holds.stream()
                .map(h -> new EventSeatStatusResponse(h.getSeatId(), h.getStatus()))
                .toList();
        return new SeatAvailabilityResponse(eventId, statuses);
    }

    @Override
    @Transactional
    public void cancelReservation(UUID reservationId, UUID authenticatedUserId) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (!isOwnerOrAdmin(reservation, authenticatedUserId)) {
            throw new ResourceNotFoundException("Reservation", reservationId);
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ConflictException("Reservation cannot be cancelled in its current state", ErrorCode.CONFLICT);
        }

        Instant now = Instant.now();
        reservation.setStatus(ReservationStatus.CANCELLED);
        for (SeatHold hold : reservation.getSeatHolds()) {
            hold.setStatus(SeatHoldStatus.RELEASED);
        }
        reservationRepository.save(reservation);

        List<UUID> seatIds = reservation.getSeatHolds().stream()
                .map(SeatHold::getSeatId)
                .toList();
        saveOutboxRecord("ReservationCancelledEvent", reservationId, new ReservationCancelledEvent(
                reservationId,
                reservation.getEventId(),
                reservation.getUserId(),
                reservation.getCustomerEmail(),
                new ArrayList<>(seatIds),
                now));

        meterRegistry.counter("seatflow.reservations.cancelled.total",
                "eventId", reservation.getEventId().toString()).increment();

        log.info("Reservation cancelled reservationId={}, eventId={}", reservationId, reservation.getEventId());
    }

    @Override
    @Transactional
    public void confirmReservation(UUID reservationId, UUID paymentId) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Reservation already confirmed. Skipping duplicate PaymentCompleted. reservationId={}, paymentId={}",
                    reservationId, paymentId);
            return;
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            log.error("Cannot confirm reservation in non-pending state. reservationId={}, paymentId={}, currentStatus={}",
                    reservationId, paymentId, reservation.getStatus());
            return;
        }

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setUpdatedAt(Instant.now());
        for (SeatHold hold : reservation.getSeatHolds()) {
            hold.setStatus(SeatHoldStatus.SOLD);
        }
        reservationRepository.save(reservation);

        meterRegistry.counter("seatflow.reservations.confirmed.total",
                "eventId", reservation.getEventId().toString(),
                "status", "SUCCESS").increment();

        log.info("Reservation confirmed via PaymentCompleted. reservationId={}, paymentId={}, seatCount={}, amount={}",
                reservationId, paymentId, reservation.getSeatHolds().size(), reservation.getTotalAmount());
    }

    @Override
    @Transactional
    public int claimGuestReservations(UUID userId, String customerEmail) {
        Objects.requireNonNull(userId, "userId is required to claim guest reservations");
        Objects.requireNonNull(customerEmail, "customerEmail is required to claim guest reservations");

        int updatedCount = reservationRepository.updateUserIdForGuestEmail(userId, customerEmail, Instant.now());

        meterRegistry.counter("seatflow.reservations.guest.claimed.total").increment(updatedCount);

        log.info("Guest reservations linked to registered user. userId={}, customerEmail={}, linkedCount={}",
                userId, customerEmail, updatedCount);
        return updatedCount;
    }

    private String resolveCustomerEmail(CreateReservationRequest request) {
        if (request.customerEmail() != null && !request.customerEmail().isBlank()) {
            return request.customerEmail();
        }
        return UserContext.getCurrentUserEmail().orElse(null);
    }

    private boolean isOwnerOrAdmin(Reservation reservation, UUID authenticatedUserId) {
        if (UserContext.hasRole(SecurityRoles.ROLE_ADMIN)) {
            return true;
        }
        return authenticatedUserId != null && authenticatedUserId.equals(reservation.getUserId());
    }

    private void saveOutboxRecord(String eventType, UUID aggregateId, Object payload) {
        String correlationId = CorrelationContext.getCorrelationId()
                .orElse(UUID.randomUUID().toString());
        EventEnvelope<?> envelope = EventEnvelope.of(
                eventType, aggregateId.toString(), correlationId, (DomainEvent) payload);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event " + eventType, e);
        }
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(json)
                .retryCount(0)
                .build();
        outboxEventRepository.save(outboxEvent);
    }
}
