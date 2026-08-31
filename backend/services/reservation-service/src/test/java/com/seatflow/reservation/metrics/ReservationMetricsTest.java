package com.seatflow.reservation.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.mapper.ReservationMapper;
import com.seatflow.reservation.model.entity.OutboxEvent;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.service.impl.ReservationServiceImpl;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationMetricsTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private SeatHoldRepository seatHoldRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ReservationMapper reservationMapper;
    @Mock private EventClient eventClient;
    @Mock private ObjectMapper objectMapper;
    @Mock private W3cTraceContextPropagator propagator;

    private MeterRegistry registry;
    private ReservationServiceImpl service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new ReservationServiceImpl(reservationRepository, seatHoldRepository, outboxEventRepository,
                reservationMapper, eventClient, objectMapper, registry, propagator);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CreateReservationRequest req(UUID eventId, List<UUID> seatIds, List<BigDecimal> prices, String key) {
        return new CreateReservationRequest(eventId, "guest@example.com", seatIds, prices, key);
    }

    private Reservation stub(UUID id, UUID eventId) {
        return Reservation.builder()
                .id(id)
                .eventId(eventId)
                .customerEmail("guest@example.com")
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey("k")
                .totalAmount(new BigDecimal("50.00"))
                .seatCount(1)
                .build();
    }

    @Test
    void shouldIncrementCreatedCounterAfterCommittedHold() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = req(eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-created");
        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", Instant.now().plusSeconds(3600), seatIds, Map.of(seatId, new BigDecimal("50.00")));

        when(eventClient.getEventSeatPricing(eq(eventId), any())).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-created")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(eventId), any())).thenReturn(List.of());
        when(reservationMapper.toEntity(any(), any())).thenReturn(stub(null, eventId));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(reservationId);
            return r;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(new com.seatflow.reservation.web.dto.response.ReservationResponse(reservationId, eventId, null, "guest@example.com", ReservationStatus.PENDING, Instant.now().plusSeconds(900), new BigDecimal("50.00"), 1, List.of(), Instant.now()));

        service.createReservation(request, null);

        assertThat(registry.find("seatflow.reservations.created.events").tags("status", "SUCCESS").counter()).isNotNull();
        assertThat(registry.find("seatflow.reservations.created.events").tags("status", "SUCCESS").counter().count()).isEqualTo(1.0);
        // high-cardinality tag must not be present — ensure no counter exists with eventId tag
        assertThat(registry.find("seatflow.reservations.created.events").tags("eventId", eventId.toString()).counter()).isNull();
        assertThat(registry.find("seatflow.reservations.created.events").tags("status", "SUCCESS").counter().getId().getTag("eventId")).isNull();
    }

    @Test
    void shouldIncrementConflictCounterOnAlreadyHeld() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = req(eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-conflict");
        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", Instant.now().plusSeconds(3600), seatIds, Map.of(seatId, new BigDecimal("50.00")));
        when(eventClient.getEventSeatPricing(eq(eventId), any())).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-conflict")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(eventId), any())).thenReturn(List.of(SeatHold.builder().seatId(seatId).status(SeatHoldStatus.HELD).build()));

        assertThatThrownBy(() -> service.createReservation(request, null)).isInstanceOf(com.seatflow.common.domain.exception.ConflictException.class);

        assertThat(registry.find("seatflow.reservations.conflicts.total").tags("reason", "ALREADY_HELD").counter()).isNotNull();
        assertThat(registry.find("seatflow.reservations.conflicts.total").tags("reason", "ALREADY_HELD").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("seatflow.reservations.conflicts.total").tags("eventId", eventId.toString()).counter()).isNull();
    }

    @Test
    void shouldIncrementConflictCounterOnLimitExceeded() {
        List<UUID> seatIds = java.util.stream.IntStream.range(0, 11).mapToObj(i -> UUID.randomUUID()).toList();
        List<BigDecimal> prices = seatIds.stream().map(s -> new BigDecimal("10.00")).toList();
        CreateReservationRequest request = req(UUID.randomUUID(), seatIds, prices, "idem-limit");

        assertThatThrownBy(() -> service.createReservation(request, null)).isInstanceOf(com.seatflow.common.domain.exception.ValidationException.class);

        assertThat(registry.find("seatflow.reservations.conflicts.total").tags("reason", "LIMIT_EXCEEDED").counter()).isNotNull();
        assertThat(registry.find("seatflow.reservations.conflicts.total").tags("reason", "LIMIT_EXCEEDED").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldRecordHoldDurationTimer() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = req(eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-dur");
        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", Instant.now().plusSeconds(3600), seatIds, Map.of(seatId, new BigDecimal("50.00")));
        when(eventClient.getEventSeatPricing(eq(eventId), any())).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-dur")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(eventId), any())).thenReturn(List.of());
        when(reservationMapper.toEntity(any(), any())).thenReturn(stub(null, eventId));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(reservationId);
            return r;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(new com.seatflow.reservation.web.dto.response.ReservationResponse(reservationId, eventId, null, "guest@example.com", ReservationStatus.PENDING, Instant.now().plusSeconds(900), new BigDecimal("50.00"), 1, List.of(), Instant.now()));

        service.createReservation(request, null);

        assertThat(registry.find("seatflow.reservations.hold.duration").timer()).isNotNull();
        assertThat(registry.find("seatflow.reservations.hold.duration").tags("outcome", "SUCCESS").timer()).isNotNull();
        assertThat(registry.find("seatflow.reservations.hold.duration").tags("outcome", "SUCCESS").timer().count()).isEqualTo(1L);
        // bounded tag check — no timer with high-cardinality tag
        assertThat(registry.find("seatflow.reservations.hold.duration").tags("eventId", eventId.toString()).timer()).isNull();
    }

    @Test
    void shouldIncrementExpiredCounterOnSweeperRelease() {
        UUID id = UUID.randomUUID();
        Reservation r = stub(id, UUID.randomUUID());
        r.setStatus(ReservationStatus.PENDING);
        SeatHold hold = SeatHold.builder().id(UUID.randomUUID()).seatId(UUID.randomUUID()).status(SeatHoldStatus.HELD).price(new BigDecimal("10.00")).build();
        r.setSeatHolds(new HashSet<>(Set.of(hold)));
        hold.setReservation(r);
        when(reservationRepository.findExpiredReservationsForUpdate(any(Instant.class), eq(100))).thenReturn(List.of(id));
        when(reservationRepository.findWithSeatHoldsById(id)).thenReturn(Optional.of(r));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        // saveOutboxRecord will call objectMapper.writeValueAsString
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", null, List.of()));
        // mock objectMapper for saveOutboxRecord – need to mock writeValueAsString
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception ignored) {}

        int processed = service.expireHoldReservations(Instant.now(), 100);
        assertThat(processed).isEqualTo(1);
        assertThat(registry.find("seatflow.reservations.expired.total").tags("outcome", "EXPIRED").counter()).isNotNull();
        assertThat(registry.find("seatflow.reservations.expired.total").tags("outcome", "EXPIRED").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldNotRollbackOnMeterFailure() throws Exception {
        // Simulate meterRegistry that throws on counter increment – service should still succeed and not propagate metric exception
        MeterRegistry failingRegistry = new SimpleMeterRegistry() {
            @Override
            public io.micrometer.core.instrument.Counter counter(String name, String... tags) {
                throw new RuntimeException("meter failure");
            }
        };
        ReservationServiceImpl failingService = new ReservationServiceImpl(reservationRepository, seatHoldRepository, outboxEventRepository,
                reservationMapper, eventClient, objectMapper, failingRegistry, propagator);

        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        CreateReservationRequest request = req(eventId, seatIds, List.of(new BigDecimal("50.00")), "idem-no-rollback");
        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED", Instant.now().plusSeconds(3600), seatIds, Map.of(seatId, new BigDecimal("50.00")));
        when(eventClient.getEventSeatPricing(eq(eventId), any())).thenReturn(pricing);
        when(reservationRepository.findWithSeatHoldsByIdempotencyKey("idem-no-rollback")).thenReturn(Optional.empty());
        when(seatHoldRepository.findAndLockSeatsForUpdate(eq(eventId), any())).thenReturn(List.of());
        when(reservationMapper.toEntity(any(), any())).thenReturn(stub(null, eventId));
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(reservationId);
            return r;
        });
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(reservationMapper.toResponse(any())).thenReturn(new com.seatflow.reservation.web.dto.response.ReservationResponse(reservationId, eventId, null, "guest@example.com", ReservationStatus.PENDING, Instant.now().plusSeconds(900), new BigDecimal("50.00"), 1, List.of(), Instant.now()));

        // should not throw despite meter failure
        var resp = failingService.createReservation(request, null);
        assertThat(resp).isNotNull();
    }
}
