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
import com.seatflow.reservation.client.dto.PricingTierClientDto;
import com.seatflow.reservation.client.dto.SeatPricingDetails;
import com.seatflow.reservation.mapper.ReservationMapper;
import com.seatflow.reservation.messaging.event.ReservationCancelledEvent;
import com.seatflow.reservation.messaging.event.ReservationConfirmedEvent;
import com.seatflow.reservation.messaging.event.ReservationExpiredEvent;
import com.seatflow.reservation.messaging.event.ReservationHeldEvent;
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
import com.seatflow.reservation.web.dto.request.SeatPricingSelectionRequest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    private TransactionTemplate transactionTemplate;

    @Autowired
    void configureTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ReservationResponse createReservation(CreateReservationRequest request, UUID authenticatedUserId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Instant startTime = Instant.now();
        try {
            validateSeatLists(request);
            String customerEmail = resolveCustomerEmail(request);
            if (customerEmail == null) {
                throw new ValidationException("customerEmail is required (provide in body or via authenticated JWT)",
                        ErrorCode.INVALID_REQUEST);
            }
            EventPricingDetails eventPricing = eventClient.getEventSeatPricing(
                    request.eventId(), new HashSet<>(request.seatIds()));
            validatePerSeatPricing(request, eventPricing.seatPrices());

            return executeCreateReservationTransaction(request, authenticatedUserId, customerEmail, eventPricing);
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

    @Transactional
    public ReservationResponse createReservationTransactional(CreateReservationRequest request,
                                                                   UUID authenticatedUserId,
                                                                   String customerEmail,
                                                                   EventPricingDetails eventPricing) {
        Optional<Reservation> existing = reservationRepository.findWithSeatHoldsByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Reservation prior = existing.get();
            Set<UUID> priorSeats = prior.getSeatHolds().stream()
                    .map(SeatHold::getSeatId)
                    .collect(Collectors.toSet());
            if (priorSeats.equals(new HashSet<>(request.seatIds()))) {
                log.info("Idempotent reservation replay. reservationId={}", prior.getId());
                return reservationMapper.toResponse(prior);
            }
            throw new ConflictException("Idempotency key reused with different seats", ErrorCode.CONFLICT);
        }

        List<UUID> sortedSeatIds = new ArrayList<>(request.seatIds());
        sortedSeatIds.sort(UUID::compareTo);
        List<SeatHold> conflicting = seatHoldRepository.findAndLockSeatsForUpdate(
                request.eventId(), sortedSeatIds);
        if (!conflicting.isEmpty()) {
            List<UUID> conflictingSeatIds = conflicting.stream().map(SeatHold::getSeatId).toList();
            log.warn("Seat hold collision detected. eventId={}, seatsCount={}",
                    request.eventId(), conflictingSeatIds.size());
            throw new ConflictException("One or more seats are already held or sold", ErrorCode.SEAT_ALREADY_RESERVED);
        }

        BigDecimal authoritativeTotal = eventPricing.seatPrices().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Reservation reservation = reservationMapper.toEntity(request, authenticatedUserId);
        reservation.setCustomerEmail(customerEmail);
        reservation.setTotalAmount(authoritativeTotal);
        reservation.setExpiresAt(Instant.now().plus(HOLD_DURATION));

        Map<UUID, BigDecimal> priceMap = eventPricing.seatPrices();
        for (UUID seatId : request.seatIds()) {
            SeatPricingDetails details = eventPricing.seatDetails().get(seatId);
            PricingTierClientDto defaultTier = details == null ? null : details.pricingTiers().stream()
                    .filter(tier -> tier.price() != null && tier.price().compareTo(priceMap.get(seatId)) == 0)
                    .findFirst()
                    .orElse(null);
            SeatHold hold = SeatHold.builder()
                    .eventId(request.eventId())
                    .seatId(seatId)
                    .status(SeatHoldStatus.HELD)
                    .price(priceMap.getOrDefault(seatId, BigDecimal.ZERO))
                    .rowLabel(details == null ? null : details.rowLabel())
                    .seatNumber(details == null ? null : details.seatNumber())
                    .pricingTierId(defaultTier == null ? null : defaultTier.id())
                    .ticketType(defaultTier == null ? null : defaultTier.categoryName())
                    .build();
            reservation.addSeatHold(hold);
        }

        Reservation saved;
        try {
            saved = reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException ex) {
            Optional<Reservation> prior = reservationRepository.findWithSeatHoldsByIdempotencyKey(request.idempotencyKey());
            if (prior.isPresent()) {
                Set<UUID> priorSeats = prior.get().getSeatHolds().stream()
                        .map(SeatHold::getSeatId)
                        .collect(Collectors.toSet());
                if (priorSeats.equals(new HashSet<>(request.seatIds()))) {
                    log.info("Idempotent reservation replay after constraint violation. reservationId={}",
                            prior.get().getId());
                    return reservationMapper.toResponse(prior.get());
                }
                throw new ConflictException("Idempotency key reused with different seats", ErrorCode.CONFLICT);
            }
            log.warn("Concurrent seat hold detected. eventId={}, seatsCount={}",
                    request.eventId(), request.seatIds().size(), ex);
            throw new ConflictException("One or more seats were taken concurrently", ErrorCode.SEAT_ALREADY_RESERVED);
        }

        Instant now = Instant.now();
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

        log.info("Reservation hold acquired successfully. reservationId={}, eventId={}, userId={}, seatsCount={}, totalAmount={}, expiresAt={}",
                saved.getId(), saved.getEventId(), authenticatedUserId, request.seatIds().size(),
                saved.getTotalAmount(), saved.getExpiresAt());

        return reservationMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getReservationById(UUID reservationId, UUID authenticatedUserId, String customerEmailProof) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (!isOwnerOrAdmin(reservation, authenticatedUserId, customerEmailProof)) {
            throw new ResourceNotFoundException("Reservation", reservationId);
        }

        return reservationMapper.toResponse(reservation);
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getReservationByIdInternal(UUID reservationId) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));
        return reservationMapper.toResponse(reservation);
    }

    private ReservationResponse executeCreateReservationTransaction(CreateReservationRequest request,
                                                                     UUID authenticatedUserId,
                                                                     String customerEmail,
                                                                     EventPricingDetails eventPricing) {
        // The fallback keeps direct unit construction usable; Spring-managed instances always
        // receive the transaction template through configureTransactionTemplate().
        if (transactionTemplate == null) {
            return createReservationTransactional(request, authenticatedUserId, customerEmail, eventPricing);
        }
        return transactionTemplate.execute(status ->
                createReservationTransactional(request, authenticatedUserId, customerEmail, eventPricing));
    }

    @Override
    public ReservationResponse updateReservationPricing(UUID reservationId,
                                                        SeatPricingSelectionRequest request,
                                                        UUID authenticatedUserId,
                                                        String customerEmailProof) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (!isOwnerOrAdmin(reservation, authenticatedUserId, customerEmailProof)) {
            throw new ResourceNotFoundException("Reservation", reservationId);
        }

        ensureReservationPricingIsMutable(reservation);
        Set<UUID> heldSeatIds = validatePricingSelectionShape(reservation, request);

        // Resolve pricing outside the database transaction. The transactional phase below
        // re-reads and re-validates the reservation before applying the returned prices.
        EventPricingDetails eventPricing = eventClient.getEventSeatPricing(reservation.getEventId(), heldSeatIds);
        return executeUpdateReservationPricingTransaction(
                reservationId, request, authenticatedUserId, customerEmailProof, eventPricing);
    }

    @Transactional
    ReservationResponse updateReservationPricingTransactional(UUID reservationId,
                                                               SeatPricingSelectionRequest request,
                                                               UUID authenticatedUserId,
                                                               String customerEmailProof,
                                                               EventPricingDetails eventPricing) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (!isOwnerOrAdmin(reservation, authenticatedUserId, customerEmailProof)) {
            throw new ResourceNotFoundException("Reservation", reservationId);
        }
        ensureReservationPricingIsMutable(reservation);

        Map<UUID, SeatPricingSelectionRequest.SeatPricingSelection> selections =
                pricingSelectionsBySeat(reservation, request);
        BigDecimal total = BigDecimal.ZERO;
        for (SeatHold hold : reservation.getSeatHolds()) {
            if (hold.getStatus() != SeatHoldStatus.HELD) {
                continue;
            }
            SeatPricingSelectionRequest.SeatPricingSelection selection = selections.get(hold.getSeatId());
            SeatPricingDetails seatDetails = eventPricing.seatDetails().get(hold.getSeatId());
            if (seatDetails == null) {
                throw new ValidationException("Seat pricing is unavailable for " + hold.getSeatId(), ErrorCode.INVALID_REQUEST);
            }
            PricingTierClientDto tier = seatDetails.pricingTiers().stream()
                    .filter(candidate -> candidate.id().equals(selection.pricingTierId()))
                    .findFirst()
                    .orElseThrow(() -> new ValidationException(
                            "Selected ticket type is not available for seat " + hold.getSeatId(),
                            ErrorCode.INVALID_REQUEST));
            hold.setPrice(tier.price());
            hold.setPricingTierId(tier.id());
            hold.setTicketType(tier.categoryName());
            hold.setRowLabel(seatDetails.rowLabel());
            hold.setSeatNumber(seatDetails.seatNumber());
            total = total.add(tier.price());
        }
        reservation.setTotalAmount(total);
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        log.info("Reservation ticket types updated. reservationId={}, seatsCount={}, totalAmount={}",
                reservationId, selections.size(), total);
        return reservationMapper.toResponse(saved);
    }

    private ReservationResponse executeUpdateReservationPricingTransaction(UUID reservationId,
                                                                            SeatPricingSelectionRequest request,
                                                                            UUID authenticatedUserId,
                                                                            String customerEmailProof,
                                                                            EventPricingDetails eventPricing) {
        if (transactionTemplate == null) {
            return updateReservationPricingTransactional(
                    reservationId, request, authenticatedUserId, customerEmailProof, eventPricing);
        }
        return transactionTemplate.execute(status -> updateReservationPricingTransactional(
                reservationId, request, authenticatedUserId, customerEmailProof, eventPricing));
    }

    private void ensureReservationPricingIsMutable(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new ConflictException("Ticket types can only be changed while the reservation is pending", ErrorCode.CONFLICT);
        }
        if (reservation.getExpiresAt() == null || !reservation.getExpiresAt().isAfter(Instant.now())) {
            throw new ValidationException("Reservation hold has expired", ErrorCode.RESERVATION_EXPIRED);
        }
    }

    private Set<UUID> validatePricingSelectionShape(Reservation reservation,
                                                    SeatPricingSelectionRequest request) {
        return pricingSelectionsBySeat(reservation, request).keySet();
    }

    private Map<UUID, SeatPricingSelectionRequest.SeatPricingSelection> pricingSelectionsBySeat(
            Reservation reservation, SeatPricingSelectionRequest request) {
        if (request == null || request.seats() == null) {
            throw new ValidationException("A pricing tier must be selected for every held seat", ErrorCode.INVALID_REQUEST);
        }
        Map<UUID, SeatPricingSelectionRequest.SeatPricingSelection> selections = request.seats().stream()
                .collect(Collectors.toMap(
                        SeatPricingSelectionRequest.SeatPricingSelection::seatId,
                        selection -> selection,
                        (first, duplicate) -> first));
        Set<UUID> heldSeatIds = reservation.getSeatHolds().stream()
                .filter(hold -> hold.getStatus() == SeatHoldStatus.HELD)
                .map(SeatHold::getSeatId)
                .collect(Collectors.toSet());
        if (selections.size() != request.seats().size() || !selections.keySet().equals(heldSeatIds)) {
            throw new ValidationException("A pricing tier must be selected for every held seat", ErrorCode.INVALID_REQUEST);
        }
        return selections;
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
    public void cancelReservation(UUID reservationId, UUID authenticatedUserId, String customerEmailProof) {
        Reservation reservation = reservationRepository.findWithSeatHoldsById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));

        if (!isOwnerOrAdmin(reservation, authenticatedUserId, customerEmailProof)) {
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
        for (SeatHold hold : reservation.getSeatHolds()) {
            hold.setStatus(SeatHoldStatus.SOLD);
        }
        reservationRepository.save(reservation);

        List<UUID> seatIds = reservation.getSeatHolds().stream()
                .map(SeatHold::getSeatId)
                .toList();
        saveOutboxRecord("ReservationConfirmedEvent", reservationId, new ReservationConfirmedEvent(
                reservationId,
                reservation.getEventId(),
                reservation.getUserId(),
                reservation.getCustomerEmail(),
                new ArrayList<>(seatIds),
                reservation.getTotalAmount(),
                paymentId,
                Instant.now()));

        meterRegistry.counter("seatflow.reservations.confirmed.total",
                "eventId", reservation.getEventId().toString(),
                "status", "SUCCESS").increment();

        log.info("Reservation confirmed via PaymentCompleted. reservationId={}, paymentId={}, seatsCount={}, totalAmount={}",
                reservationId, paymentId, reservation.getSeatHolds().size(), reservation.getTotalAmount());
    }

    @Override
    @Transactional
    public int claimGuestReservations(UUID userId, String customerEmail) {
        Objects.requireNonNull(userId, "userId is required to claim guest reservations");
        String normalizedEmail = normalizeEmail(customerEmail);
        Objects.requireNonNull(normalizedEmail, "customerEmail is required to claim guest reservations");

        int updatedCount = reservationRepository.updateUserIdForGuestEmail(userId, normalizedEmail, Instant.now());

        meterRegistry.counter("seatflow.reservations.guest.claimed.total").increment(updatedCount);

        log.info("Guest reservations linked to registered user. userId={}, customerEmail={}, linkedCount={}",
                userId, normalizedEmail, updatedCount);
        return updatedCount;
    }

    @Override
    @Transactional
    public int expireHoldReservations(Instant now, int batchSize) {
        List<UUID> expiredIds = reservationRepository.findExpiredReservationsForUpdate(now, batchSize);
        if (expiredIds.isEmpty()) {
            return 0;
        }

        log.info("Processing expired reservations sweep. count={}", expiredIds.size());

        int processed = 0;
        for (UUID id : expiredIds) {
            Reservation reservation = reservationRepository.findWithSeatHoldsById(id).orElse(null);
            if (reservation == null || reservation.getStatus() != ReservationStatus.PENDING) {
                continue;
            }

            reservation.setStatus(ReservationStatus.EXPIRED);
            reservation.setUpdatedAt(now);

            List<UUID> releasedSeatIds = new ArrayList<>();
            for (SeatHold hold : reservation.getSeatHolds()) {
                hold.setStatus(SeatHoldStatus.RELEASED);
                releasedSeatIds.add(hold.getSeatId());
            }

            reservationRepository.save(reservation);

            saveOutboxRecord("ReservationExpiredEvent", reservation.getId(), new ReservationExpiredEvent(
                    reservation.getId(),
                    reservation.getEventId(),
                    releasedSeatIds,
                    "HOLD_TIMEOUT_EXCEEDED",
                    now));

            log.info("Reservation expired and seat holds released. reservationId={}, eventId={}, seatsCount={}",
                    reservation.getId(), reservation.getEventId(), releasedSeatIds.size());
            processed++;
        }

        meterRegistry.counter("seatflow.reservations.expired.total").increment(processed);
        return processed;
    }

    private void validateSeatLists(CreateReservationRequest request) {
        if (request.seatIds() == null || request.seatIds().isEmpty()) {
            throw new ValidationException("At least one seat must be selected", ErrorCode.INVALID_REQUEST);
        }
        if (request.seatIds().size() > MAX_SEATS_PER_RESERVATION) {
            throw new ValidationException("Cannot reserve more than 10 seats per reservation", ErrorCode.MAX_SEATS_EXCEEDED);
        }
        if (request.seatPrices() == null || request.seatPrices().size() != request.seatIds().size()) {
            throw new ValidationException("seatPrices size must match seatIds size", ErrorCode.INVALID_REQUEST);
        }
        if (new HashSet<>(request.seatIds()).size() != request.seatIds().size()) {
            throw new ValidationException("Duplicate seat IDs are not allowed", ErrorCode.INVALID_REQUEST);
        }
    }

    private void validatePerSeatPricing(CreateReservationRequest request, Map<UUID, BigDecimal> authoritativePrices) {
        List<UUID> seatIds = request.seatIds();
        List<BigDecimal> seatPrices = request.seatPrices();
        for (int i = 0; i < seatIds.size(); i++) {
            UUID seatId = seatIds.get(i);
            BigDecimal clientPrice = seatPrices.get(i);
            BigDecimal authoritative = authoritativePrices.get(seatId);
            if (authoritative == null) {
                throw new ValidationException("Seat is not available for pricing: " + seatId, ErrorCode.INVALID_REQUEST);
            }
            if (clientPrice == null || clientPrice.compareTo(authoritative) != 0) {
                throw new ValidationException("Client price does not match server computed price for seat " + seatId,
                        ErrorCode.INVALID_REQUEST);
            }
        }
    }

    private String resolveCustomerEmail(CreateReservationRequest request) {
        if (request.customerEmail() != null && !request.customerEmail().isBlank()) {
            return normalizeEmail(request.customerEmail());
        }
        return UserContext.getCurrentUserEmail().map(this::normalizeEmail).orElse(null);
    }

    private boolean isOwnerOrAdmin(Reservation reservation, UUID authenticatedUserId, String customerEmailProof) {
        if (UserContext.hasRole(SecurityRoles.ROLE_ADMIN)) {
            return true;
        }
        if (authenticatedUserId != null && authenticatedUserId.equals(reservation.getUserId())) {
            return true;
        }
        String callerEmail = UserContext.getCurrentUserEmail().orElse(null);
        String proof = normalizeEmail(customerEmailProof);
        return (proof != null && proof.equalsIgnoreCase(reservation.getCustomerEmail()))
                || (callerEmail != null && callerEmail.equalsIgnoreCase(reservation.getCustomerEmail()));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
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
