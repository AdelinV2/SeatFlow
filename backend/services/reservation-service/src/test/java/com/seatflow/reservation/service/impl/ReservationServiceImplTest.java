package com.seatflow.reservation.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.PricingTierClientDto;
import com.seatflow.reservation.client.dto.SeatPricingDetails;
import com.seatflow.reservation.mapper.ReservationMapper;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private EventClient eventClient;
    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationServiceImpl(reservationRepository, seatHoldRepository, outboxEventRepository,
                reservationMapper, eventClient, objectMapper, meterRegistry);
        CorrelationContext.setCorrelationId("test-correlation");
    }

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
        SecurityContextHolder.clearContext();
    }

    private CreateReservationRequest buildRequest(UUID eventId, List<UUID> seatIds, List<BigDecimal> prices, String idem) {
        return new CreateReservationRequest(eventId, "guest@example.com", seatIds, prices, idem);
    }

    private Reservation stubReservation(UUID id, UUID eventId, UUID userId, ReservationStatus status, Set<SeatHold> holds) {
        return Reservation.builder()
                .id(id)
                .eventId(eventId)
                .userId(userId)
                .customerEmail("guest@example.com")
                .status(status)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey("idem")
                .totalAmount(new BigDecimal("100.00"))
                .seatCount(holds.size())
                .seatHolds(holds)
                .build();
    }

    private ReservationResponse sampleResponse(UUID id, UUID eventId) {
        return new ReservationResponse(id, eventId, null, "guest@example.com", ReservationStatus.PENDING,
                Instant.now().plusSeconds(900), new BigDecimal("50.00"), 1, List.of(), Instant.now());
    }

    @Test
    void createReservationSucceedsAndPublishesOutbox() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-1");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                Instant.now().plusSeconds(3600), seatIds, Map.of(seatId, new BigDecimal("50.00")));

        when(reservationMapper.toEntity(any(), any())).thenReturn(stubReservation(null, eventId, null, ReservationStatus.PENDING, new HashSet<>()));
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(eventId), eq(seatIds))).thenReturn(List.of());
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(reservationId);
            return r;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(reservationId, eventId));

        ReservationResponse result = service.createReservation(request, null);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(reservationId);
        verify(eventClient).getEventSeatPricing(eventId, new HashSet<>(seatIds));
        verify(reservationRepository).saveAndFlush(any(Reservation.class));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void createReservationLocksSeatsInSortedUuidOrder() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID firstSeat = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondSeat = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID reservationId = UUID.randomUUID();
        List<UUID> requestedSeatIds = List.of(secondSeat, firstSeat);
        List<UUID> sortedSeatIds = List.of(firstSeat, secondSeat);
        CreateReservationRequest request = buildRequest(eventId, requestedSeatIds,
                List.of(new BigDecimal("20.00"), new BigDecimal("10.00")), "idem-lock-order");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                Instant.now().plusSeconds(3600), requestedSeatIds,
                Map.of(firstSeat, new BigDecimal("10.00"), secondSeat, new BigDecimal("20.00")));

        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(requestedSeatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-lock-order")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eventId, sortedSeatIds)).thenReturn(List.of());
        when(reservationMapper.toEntity(any(), any()))
                .thenReturn(stubReservation(null, eventId, null, ReservationStatus.PENDING, new HashSet<>()));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(reservationId);
            return reservation;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(reservationId, eventId));

        service.createReservation(request, null);

        verify(seatHoldRepository).findAndLockSeatsForUpdate(eventId, sortedSeatIds);
    }

    @Test
    void createReservationRejectsMoreThanTenSeats() {
        List<UUID> seats = java.util.stream.IntStream.range(0, 11).mapToObj(i -> UUID.randomUUID()).toList();
        CreateReservationRequest request = buildRequest(UUID.randomUUID(), seats,
                seats.stream().map(s -> new BigDecimal("10.00")).toList(), "idem-11");

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MAX_SEATS_EXCEEDED);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationReplaysOnSameIdempotencyKeyAndSeats() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-replay");

        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation prior = stubReservation(reservationId, eventId, null, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                Instant.now().plusSeconds(3600), seatIds, Map.of(seatId, new BigDecimal("50.00")));
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-replay")).thenReturn(Optional.of(prior));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(reservationId, eventId));

        ReservationResponse result = service.createReservation(request, null);

        assertThat(result.id()).isEqualTo(reservationId);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationRejectsConflictingSeats() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-conflict");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                Instant.now().plusSeconds(3600), seatIds, Map.of(seatId, new BigDecimal("50.00")));
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-conflict")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(eventId), eq(seatIds)))
                .thenReturn(List.of(SeatHold.builder().seatId(seatId).status(SeatHoldStatus.HELD).build()));

        ConflictException ex = assertThrows(ConflictException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_RESERVED);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void createReservationRejectsClientPriceDrift() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(eventId, seatIds, List.of(new BigDecimal("60.00")), "idem-price");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                Instant.now().plusSeconds(3600), seatIds, Map.of(seatId, new BigDecimal("50.00")));
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void getReservationByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getReservationById(id, UUID.randomUUID(), null));
    }

    @Test
    void getReservationByIdThrowsOnOwnerMismatchForNonAdmin() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), owner, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("p", null, List.of()));

        assertThrows(ResourceNotFoundException.class, () -> service.getReservationById(id, stranger, null));
    }

    @Test
    void getReservationByIdReturnsForAdminRegardlessOfOwner() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), owner, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, res.getEventId()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("p", null, List.of(new SimpleGrantedAuthority(SecurityRoles.ROLE_ADMIN))));

        ReservationResponse result = service.getReservationById(id, caller, null);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(id);
    }

    @Test
    void getReservationByIdReturnsForOwner() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), owner, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, res.getEventId()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("p", null, List.of()));

        ReservationResponse result = service.getReservationById(id, owner, null);

        assertThat(result).isNotNull();
    }

    @Test
    void updateReservationPricingResolvesSelectedTierAndRecalculatesTotal() {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, eventId, null, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        PricingTierClientDto tier = new PricingTierClientDto(tierId, UUID.randomUUID(), "Student",
                new BigDecimal("35.00"), "USD");
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(eventClient.getEventSeatPricing(eventId, Set.of(seatId))).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", Instant.now().plusSeconds(3600), List.of(seatId),
                Map.of(seatId, new BigDecimal("50.00")),
                Map.of(seatId, new SeatPricingDetails(UUID.randomUUID(), "Orchestra", "B", 7, List.of(tier)))));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, eventId));

        service.updateReservationPricing(id,
                new SeatPricingSelectionRequest(List.of(
                        new SeatPricingSelectionRequest.SeatPricingSelection(seatId, tierId))),
                null, "guest@example.com");

        assertThat(hold.getPrice()).isEqualByComparingTo("35.00");
        assertThat(hold.getTicketType()).isEqualTo("Student");
        assertThat(hold.getRowLabel()).isEqualTo("B");
        assertThat(hold.getSeatNumber()).isEqualTo(7);
        assertThat(res.getTotalAmount()).isEqualByComparingTo("35.00");
        verify(reservationRepository).saveAndFlush(res);
    }

    @Test
    void getReservationByIdRejectsAnonymousGuestWithoutEmailProof() {
        UUID id = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        res.setCustomerEmail("guest@example.com");
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        assertThrows(ResourceNotFoundException.class, () -> service.getReservationById(id, null, null));
        verify(reservationMapper, never()).toResponse(any());
    }

    @Test
    void updateReservationPricingRejectsAnonymousGuestWithoutEmailProof() {
        UUID id = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateReservationPricing(id, null, null, null));
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelReservationRejectsAnonymousGuestWithoutEmailProof() {
        UUID id = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));

        assertThrows(ResourceNotFoundException.class, () -> service.cancelReservation(id, null, null));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void getReservationByIdReturnsForAnonymousGuestWithValidProof() {
        UUID id = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        res.setCustomerEmail("guest@example.com");
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, res.getEventId()));

        ReservationResponse result = service.getReservationById(id, null, "guest@example.com");

        assertThat(result).isNotNull();
    }

    @Test
    void getReservationByIdThrowsForAnonymousGuestWithInvalidProof() {
        UUID id = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        res.setCustomerEmail("guest@example.com");
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));

        assertThrows(ResourceNotFoundException.class, () -> service.getReservationById(id, null, "wrong@example.com"));
    }

    @Test
    void cancelReservationReleasesSeatsAndPublishesOutbox() throws Exception {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, eventId, userId, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("p", null, List.of()));

        service.cancelReservation(id, userId, null);

        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(res.getSeatHolds()).allMatch(h -> h.getStatus() == SeatHoldStatus.RELEASED);
        verify(reservationRepository).save(any(Reservation.class));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void cancelReservationRejectsNonPendingState() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), userId, ReservationStatus.CONFIRMED, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("p", null, List.of()));

        ConflictException ex = assertThrows(ConflictException.class, () -> service.cancelReservation(id, userId, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void getSeatAvailabilityReturnsLiveStatuses() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        ActiveSeatHoldProjection projection = mock(ActiveSeatHoldProjection.class);
        when(projection.getSeatId()).thenReturn(seatId);
        when(projection.getStatus()).thenReturn(SeatHoldStatus.HELD);
        when(seatHoldRepository.findActiveSeatHoldsByEventId(eventId)).thenReturn(List.of(projection));

        SeatAvailabilityResponse result = service.getSeatAvailability(eventId);

        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.seatStatuses()).hasSize(1);
        EventSeatStatusResponse status = result.seatStatuses().get(0);
        assertThat(status.seatId()).isEqualTo(seatId);
        assertThat(status.status()).isEqualTo(SeatHoldStatus.HELD);
    }

    @Test
    void createReservationRejectsDuplicateSeatIds() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId, seatId);
        CreateReservationRequest request = buildRequest(eventId, seatIds,
                List.of(new BigDecimal("50.00"), new BigDecimal("50.00")), "idem-dup");

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationRejectsSeatPriceSizeMismatch() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(eventId, seatIds,
                List.of(new BigDecimal("50.00"), new BigDecimal("50.00")), "idem-size");

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationRejectsPerSeatPriceSwap() {
        UUID eventId = UUID.randomUUID();
        UUID seatA = UUID.randomUUID();
        UUID seatB = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatA, seatB);
        CreateReservationRequest request = buildRequest(eventId, seatIds,
                List.of(new BigDecimal("90.00"), new BigDecimal("10.00")), "idem-swap");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                Instant.now().plusSeconds(3600), seatIds,
                Map.of(seatA, new BigDecimal("10.00"), seatB, new BigDecimal("90.00")));
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void getReservationReturnsForGuestOwnerWithMatchingEmailProof() {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation res = stubReservation(id, eventId, null, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, eventId));

        ReservationResponse result = service.getReservationById(id, null, "Guest@Example.com");

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(id);
    }

    @Test
    void getReservationThrowsForGuestOwnerWithWrongEmailProof() {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation res = stubReservation(id, eventId, null, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));

        assertThrows(ResourceNotFoundException.class, () -> service.getReservationById(id, null, "other@example.com"));
    }

    @Test
    void cancelReservationSucceedsForGuestOwnerWithMatchingEmailProof() throws Exception {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, eventId, null, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("p", null, List.of()));

        service.cancelReservation(id, null, "guest@example.com");

        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void confirmReservationPublishesReservationConfirmedOutbox() throws Exception {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, eventId, userId, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.confirmReservation(id, paymentId);

        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(res.getSeatHolds()).allMatch(h -> h.getStatus() == SeatHoldStatus.SOLD);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }
}
