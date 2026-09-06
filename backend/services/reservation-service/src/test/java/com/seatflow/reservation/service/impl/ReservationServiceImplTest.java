package com.seatflow.reservation.service.impl;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.PricingTierClientDto;
import com.seatflow.reservation.client.dto.SeatPricingDetails;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
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
import static org.mockito.Mockito.lenient;
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
    @Mock
    private W3cTraceContextPropagator w3cTraceContextPropagator;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationServiceImpl(reservationRepository, seatHoldRepository, outboxEventRepository,
                reservationMapper, eventClient, objectMapper, meterRegistry, w3cTraceContextPropagator);
        CorrelationContext.setCorrelationId("test-correlation");
    }

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
        SecurityContextHolder.clearContext();
    }

    private CreateReservationRequest buildRequest(UUID sessionId, UUID ignoredLegacyEventId,
                                                 List<UUID> seatIds, List<BigDecimal> prices, String idem) {
        // P12-007: client request carries session only; parent event derives server-side.
        return new CreateReservationRequest(sessionId, "guest@example.com", seatIds, prices, idem);
    }

    private SessionBookingContextDto bookingContext(UUID sessionId, UUID eventId) {
        return new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID());
    }

    private void stubBookableSession(UUID sessionId, UUID eventId) {
        lenient().when(eventClient.getSessionBookingContext(sessionId))
                .thenReturn(bookingContext(sessionId, eventId));
    }

    private Reservation stubReservation(UUID id, UUID sessionId, UUID eventId, UUID userId,
                                        ReservationStatus status, Set<SeatHold> holds) {
        return Reservation.builder()
                .id(id)
                .eventSessionId(sessionId)
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

    private ReservationResponse sampleResponse(UUID id, UUID sessionId, UUID eventId) {
        return new ReservationResponse(id, sessionId, eventId, null, "guest@example.com", ReservationStatus.PENDING,
                Instant.now().plusSeconds(900), new BigDecimal("50.00"), 1,
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000), null,
                List.of(), Instant.now());
    }

    @Test
    void createReservationSucceedsAndPublishesOutbox() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-1");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", seatIds, Map.of(seatId, new BigDecimal("50.00")));

        when(reservationMapper.toEntity(any(), any())).thenReturn(stubReservation(null, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>()));
        stubBookableSession(sessionId, eventId);
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(sessionId), eq(seatIds))).thenReturn(List.of());
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(reservationId);
            return r;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(reservationId, sessionId, eventId));

        ReservationResponse result = service.createReservation(request, null);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(reservationId);
        verify(eventClient).getSessionBookingContext(sessionId);
        verify(eventClient).getEventSeatPricing(eventId, new HashSet<>(seatIds));
        verify(reservationRepository).saveAndFlush(any(Reservation.class));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void createReservationLocksSeatsInSortedUuidOrder() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID firstSeat = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondSeat = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID reservationId = UUID.randomUUID();
        List<UUID> requestedSeatIds = List.of(secondSeat, firstSeat);
        List<UUID> sortedSeatIds = List.of(firstSeat, secondSeat);
        CreateReservationRequest request = buildRequest(sessionId, eventId, requestedSeatIds,
                List.of(new BigDecimal("20.00"), new BigDecimal("10.00")), "idem-lock-order");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                requestedSeatIds,
                Map.of(firstSeat, new BigDecimal("10.00"), secondSeat, new BigDecimal("20.00")));

        stubBookableSession(sessionId, eventId);
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(requestedSeatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-lock-order")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(sessionId, sortedSeatIds)).thenReturn(List.of());
        when(reservationMapper.toEntity(any(), any()))
                .thenReturn(stubReservation(null, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>()));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(reservationId);
            return reservation;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(reservationId, sessionId, eventId));

        service.createReservation(request, null);

        verify(seatHoldRepository).findAndLockSeatsForUpdate(sessionId, sortedSeatIds);
    }

    @Test
    void createReservationRejectsMoreThanTenSeats() {
        List<UUID> seats = java.util.stream.IntStream.range(0, 11).mapToObj(i -> UUID.randomUUID()).toList();
        CreateReservationRequest request = buildRequest(UUID.randomUUID(), UUID.randomUUID(), seats,
                seats.stream().map(s -> new BigDecimal("10.00")).toList(), "idem-11");

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MAX_SEATS_EXCEEDED);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationReplaysOnSameIdempotencyKeySeatsAndSession() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-replay");

        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).eventSessionId(sessionId).eventId(eventId).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation prior = stubReservation(reservationId, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", seatIds, Map.of(seatId, new BigDecimal("50.00")));
        stubBookableSession(sessionId, eventId);
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-replay")).thenReturn(Optional.of(prior));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(reservationId, sessionId, eventId));

        ReservationResponse result = service.createReservation(request, null);

        assertThat(result.id()).isEqualTo(reservationId);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationRejectsIdempotencyReuseAcrossSessions() {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(sessionB, eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-cross");

        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).eventSessionId(sessionA).eventId(eventId).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation prior = stubReservation(UUID.randomUUID(), sessionA, eventId, null, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", seatIds, Map.of(seatId, new BigDecimal("50.00")));
        stubBookableSession(sessionB, eventId);
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-cross")).thenReturn(Optional.of(prior));

        ConflictException ex = assertThrows(ConflictException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationRequestCarriesNoLegacyEventIdField() throws Exception {
        // P12-007: the session is the sole booking key. The request record must
        // expose no eventId component, so no caller can supply or spoof one.
        var eventIdComponent = java.util.Arrays.stream(CreateReservationRequest.class.getRecordComponents())
                .filter(c -> c.getName().equals("eventId")).findFirst();
        assertThat(eventIdComponent).isEmpty();
    }

    @Test
    void createReservationRejectsNonBookableSessionBeforeMutation() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        CreateReservationRequest request = buildRequest(sessionId, eventId,
                List.of(seatId), List.of(new BigDecimal("50.00")), "idem-cancelled");

        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "CANCELLED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID()));

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(eventClient, never()).getEventSeatPricing(any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void createReservationRejectsSaleNotOpenBeforeMutation() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        CreateReservationRequest request = buildRequest(sessionId, eventId,
                List.of(seatId), List.of(new BigDecimal("50.00")), "idem-sale-not-open");

        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                Instant.now().plusSeconds(3600), null, UUID.randomUUID()));

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ex.getMessage()).contains("have not opened yet");
        verify(eventClient, never()).getEventSeatPricing(any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void createReservationRejectsSaleClosedBeforeMutation() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        CreateReservationRequest request = buildRequest(sessionId, eventId,
                List.of(seatId), List.of(new BigDecimal("50.00")), "idem-sale-closed");

        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, Instant.now().minusSeconds(60), UUID.randomUUID()));

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ex.getMessage()).contains("have closed");
        verify(eventClient, never()).getEventSeatPricing(any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void createReservationRejectsAlreadyStartedSessionBeforeMutation() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        CreateReservationRequest request = buildRequest(sessionId, eventId,
                List.of(seatId), List.of(new BigDecimal("50.00")), "idem-started");

        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600),
                null, null, UUID.randomUUID()));

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(ex.getMessage()).contains("already started");
        verify(eventClient, never()).getEventSeatPricing(any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void createReservationPassesValidationForOpenSaleWindow() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds,
                List.of(new BigDecimal("50.00")), "idem-open-window");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", seatIds, Map.of(seatId, new BigDecimal("50.00")));

        // SCHEDULED/PUBLISHED session with an explicitly open sale window must
        // clear validateBookingContext and reach the pricing/inventory path.
        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), UUID.randomUUID()));
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationMapper.toEntity(any(), any())).thenReturn(stubReservation(null, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>()));
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-open-window")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(sessionId), eq(seatIds))).thenReturn(List.of());
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(reservationId);
            return r;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(reservationId, sessionId, eventId));

        ReservationResponse result = service.createReservation(request, null);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(reservationId);
        verify(eventClient).getSessionBookingContext(sessionId);
        verify(eventClient).getEventSeatPricing(eventId, new HashSet<>(seatIds));
        verify(reservationRepository).saveAndFlush(any(Reservation.class));
    }

    @Test
    void createReservationRejectsUnknownSessionBeforeMutation() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        CreateReservationRequest request = buildRequest(sessionId, eventId,
                List.of(seatId), List.of(new BigDecimal("50.00")), "idem-unknown");

        when(eventClient.getSessionBookingContext(sessionId))
                .thenThrow(new ValidationException("Unknown event session: " + sessionId, ErrorCode.INVALID_REQUEST));

        assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        verify(eventClient, never()).getEventSeatPricing(any(), any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationRejectsConflictingSeats() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-conflict");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", seatIds, Map.of(seatId, new BigDecimal("50.00")));
        stubBookableSession(sessionId, eventId);
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-conflict")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(sessionId), eq(seatIds)))
                .thenReturn(List.of(SeatHold.builder().seatId(seatId).status(SeatHoldStatus.HELD).build()));

        ConflictException ex = assertThrows(ConflictException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SEAT_ALREADY_RESERVED);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void createReservationRejectsClientPriceDrift() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds, List.of(new BigDecimal("60.00")), "idem-price");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", seatIds, Map.of(seatId, new BigDecimal("50.00")));
        stubBookableSession(sessionId, eventId);
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
        Reservation res = stubReservation(id, UUID.randomUUID(), UUID.randomUUID(), owner, ReservationStatus.PENDING, new HashSet<>());
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
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation res = stubReservation(id, sessionId, eventId, owner, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, sessionId, eventId));
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
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation res = stubReservation(id, sessionId, eventId, owner, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, sessionId, eventId));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("p", null, List.of()));

        ReservationResponse result = service.getReservationById(id, owner, null);

        assertThat(result).isNotNull();
    }

    @Test
    void updateReservationPricingResolvesSelectedTierAndRecalculatesTotal() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID tierId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).eventSessionId(sessionId).eventId(eventId).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        PricingTierClientDto tier = new PricingTierClientDto(tierId, UUID.randomUUID(), "Student",
                new BigDecimal("35.00"), "USD");
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        stubBookableSession(sessionId, eventId);
        when(eventClient.getEventSeatPricing(eventId, Set.of(seatId))).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", List.of(seatId),
                Map.of(seatId, new BigDecimal("50.00")),
                Map.of(seatId, new SeatPricingDetails(UUID.randomUUID(), "Orchestra", "B", 7, List.of(tier)))));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, sessionId, eventId));

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
        Reservation res = stubReservation(id, UUID.randomUUID(), UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        res.setCustomerEmail("guest@example.com");
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        assertThrows(ResourceNotFoundException.class, () -> service.getReservationById(id, null, null));
        verify(reservationMapper, never()).toResponse(any());
    }

    @Test
    void updateReservationPricingRejectsAnonymousGuestWithoutEmailProof() {
        UUID id = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateReservationPricing(id, null, null, null));
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelReservationRejectsAnonymousGuestWithoutEmailProof() {
        UUID id = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));

        assertThrows(ResourceNotFoundException.class, () -> service.cancelReservation(id, null, null));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void getReservationByIdReturnsForAnonymousGuestWithValidProof() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation res = stubReservation(id, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>());
        res.setCustomerEmail("guest@example.com");
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, sessionId, eventId));

        ReservationResponse result = service.getReservationById(id, null, "guest@example.com");

        assertThat(result).isNotNull();
    }

    @Test
    void getReservationByIdThrowsForAnonymousGuestWithInvalidProof() {
        UUID id = UUID.randomUUID();
        Reservation res = stubReservation(id, UUID.randomUUID(), UUID.randomUUID(), null, ReservationStatus.PENDING, new HashSet<>());
        res.setCustomerEmail("guest@example.com");
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));

        assertThrows(ResourceNotFoundException.class, () -> service.getReservationById(id, null, "wrong@example.com"));
    }

    @Test
    void cancelReservationReleasesSeatsAndPublishesOutbox() throws Exception {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).eventSessionId(sessionId).eventId(eventId).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, sessionId, eventId, userId, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
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
        Reservation res = stubReservation(id, UUID.randomUUID(), UUID.randomUUID(), userId, ReservationStatus.CONFIRMED, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("p", null, List.of()));

        ConflictException ex = assertThrows(ConflictException.class, () -> service.cancelReservation(id, userId, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void getSeatAvailabilityReturnsLiveStatusesForSession() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        ActiveSeatHoldProjection projection = mock(ActiveSeatHoldProjection.class);
        when(projection.getEventSessionId()).thenReturn(sessionId);
        when(projection.getEventId()).thenReturn(eventId);
        when(projection.getSeatId()).thenReturn(seatId);
        when(projection.getStatus()).thenReturn(SeatHoldStatus.HELD);
        when(seatHoldRepository.findActiveSeatHoldsByEventSessionId(sessionId)).thenReturn(List.of(projection));

        SeatAvailabilityResponse result = service.getSeatAvailability(sessionId);

        assertThat(result.eventSessionId()).isEqualTo(sessionId);
        assertThat(result.eventId()).isEqualTo(eventId);
        assertThat(result.seatStatuses()).hasSize(1);
        EventSeatStatusResponse status = result.seatStatuses().get(0);
        assertThat(status.seatId()).isEqualTo(seatId);
        assertThat(status.status()).isEqualTo(SeatHoldStatus.HELD);
    }

    @Test
    void getSeatAvailabilityReturnsNullEventIdWhenNoHolds() {
        UUID sessionId = UUID.randomUUID();
        when(seatHoldRepository.findActiveSeatHoldsByEventSessionId(sessionId)).thenReturn(List.of());

        SeatAvailabilityResponse result = service.getSeatAvailability(sessionId);

        assertThat(result.eventSessionId()).isEqualTo(sessionId);
        assertThat(result.eventId()).isNull();
        assertThat(result.seatStatuses()).isEmpty();
    }

    @Test
    void createReservationRejectsDuplicateSeatIds() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId, seatId);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds,
                List.of(new BigDecimal("50.00"), new BigDecimal("50.00")), "idem-dup");

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationRejectsSeatPriceSizeMismatch() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds,
                List.of(new BigDecimal("50.00"), new BigDecimal("50.00")), "idem-size");

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReservationRejectsPerSeatPriceSwap() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatA = UUID.randomUUID();
        UUID seatB = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatA, seatB);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds,
                List.of(new BigDecimal("90.00"), new BigDecimal("10.00")), "idem-swap");

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", seatIds,
                Map.of(seatA, new BigDecimal("10.00"), seatB, new BigDecimal("90.00")));
        stubBookableSession(sessionId, eventId);
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);

        ValidationException ex = assertThrows(ValidationException.class, () -> service.createReservation(request, null));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void getReservationReturnsForGuestOwnerWithMatchingEmailProof() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation res = stubReservation(id, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(id, sessionId, eventId));

        ReservationResponse result = service.getReservationById(id, null, "Guest@Example.com");

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(id);
    }

    @Test
    void getReservationThrowsForGuestOwnerWithWrongEmailProof() {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Reservation res = stubReservation(id, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>());
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));

        assertThrows(ResourceNotFoundException.class, () -> service.getReservationById(id, null, "other@example.com"));
    }

    @Test
    void cancelReservationSucceedsForGuestOwnerWithMatchingEmailProof() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).eventSessionId(sessionId).eventId(eventId).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
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
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).eventSessionId(sessionId).eventId(eventId).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, sessionId, eventId, userId, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.confirmReservation(id, paymentId);

        assertThat(res.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(res.getSeatHolds()).allMatch(h -> h.getStatus() == SeatHoldStatus.SOLD);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void createReservationPersistsSessionScheduleSnapshotFromTrustedBookingContext() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = buildRequest(sessionId, eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-snapshot");

        Instant startsAt = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsAt = Instant.parse("2026-10-05T21:00:00Z");
        SessionBookingContextDto context = new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED", startsAt, endsAt, null, null, UUID.randomUUID());
        lenient().when(eventClient.getSessionBookingContext(sessionId)).thenReturn(context);

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", seatIds, Map.of(seatId, new BigDecimal("50.00")));
        when(reservationMapper.toEntity(any(), any())).thenReturn(stubReservation(null, sessionId, eventId, null, ReservationStatus.PENDING, new HashSet<>()));
        when(eventClient.getEventSeatPricing(eventId, new HashSet<>(seatIds))).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-snapshot")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(sessionId), eq(seatIds))).thenReturn(List.of());
        org.mockito.ArgumentCaptor<Reservation> reservationCaptor = org.mockito.ArgumentCaptor.forClass(Reservation.class);
        when(reservationRepository.saveAndFlush(reservationCaptor.capture())).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(reservationId);
            return r;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(sampleResponse(reservationId, sessionId, eventId));

        service.createReservation(request, null);

        Reservation persisted = reservationCaptor.getValue();
        assertThat(persisted.getEventSessionId()).isEqualTo(sessionId);
        assertThat(persisted.getSessionStartsAt()).isEqualTo(startsAt);
        assertThat(persisted.getSessionEndsAt()).isEqualTo(endsAt);
    }

    @Test
    void confirmReservationOutboxCarriesStoredSessionSnapshot() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsAt = Instant.parse("2026-10-05T21:00:00Z");
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).eventSessionId(sessionId).eventId(eventId).seatId(seatId)
                .status(SeatHoldStatus.HELD).price(new BigDecimal("50.00")).build();
        Reservation res = stubReservation(id, sessionId, eventId, userId, ReservationStatus.PENDING, new HashSet<>(Set.of(hold)));
        res.setSessionStartsAt(startsAt);
        res.setSessionEndsAt(endsAt);
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(res));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        com.fasterxml.jackson.databind.ObjectMapper realMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        lenient().when(objectMapper.writeValueAsString(any()))
                .thenAnswer(inv -> realMapper.writeValueAsString(inv.getArgument(0)));
        org.mockito.ArgumentCaptor<OutboxEvent> outboxCaptor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        lenient().when(outboxEventRepository.save(outboxCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.confirmReservation(id, paymentId);

        OutboxEvent outbox = outboxCaptor.getValue();
        assertThat(outbox.getEventType()).isEqualTo("ReservationConfirmedEvent");
        com.fasterxml.jackson.databind.JsonNode payload = realMapper.readTree(outbox.getPayload());
        assertThat(payload.get("payload").get("eventSessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(payload.get("payload").get("sessionStartsAt").asText()).isEqualTo(startsAt.toString());
        assertThat(payload.get("payload").get("sessionEndsAt").asText()).isEqualTo(endsAt.toString());
    }
}
