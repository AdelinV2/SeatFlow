package com.seatflow.reservation.integration;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
import com.seatflow.reservation.migration.SessionInventoryBackfillService;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P12-003 concurrency oracle + invariant suite (real PostgreSQL via Testcontainers;
 * event-service is stubbed at the client boundary — mocks are insufficient for
 * locking, but sufficient for the trusted booking-context input).
 *
 * <p>The expiry sweep test runs last: it expires every PENDING row on purpose.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionScopedInventoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
        registry.add("reservation.cleanup.enabled", () -> "false");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private EventClient eventClient;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SessionInventoryBackfillService backfillService;

    private void stubSession(UUID sessionId, UUID eventId) {
        stubSession(sessionId, eventId, "SCHEDULED", "PUBLISHED");
    }

    private void stubSession(UUID sessionId, UUID eventId, String sessionStatus, String eventStatus) {
        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, eventStatus, sessionStatus,
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID()));
    }

    private void stubPricing(UUID eventId, Map<UUID, BigDecimal> prices) {
        List<UUID> seatIds = new ArrayList<>(prices.keySet());
        when(eventClient.getEventSeatPricing(eq(eventId), any())).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", Instant.now().plusSeconds(3600), seatIds, prices));
    }

    private CreateReservationRequest request(UUID sessionId, UUID eventId, List<UUID> seatIds,
                                             List<BigDecimal> prices, String key) {
        return new CreateReservationRequest(sessionId, eventId, "guest@seatflow.com", seatIds, prices, key);
    }

    @Test
    @Order(1)
    void heldSeatInSessionAIsStillAvailableInSessionB() {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionA, eventId);
        stubSession(sessionB, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("25.00")));

        var held = reservationService.createReservation(
                request(sessionA, eventId, List.of(seat), List.of(new BigDecimal("25.00")), "idem-a1-" + UUID.randomUUID()), null);
        assertThat(held.status()).isEqualTo(ReservationStatus.PENDING);

        var availabilityA = reservationService.getSeatAvailability(sessionA);
        assertThat(availabilityA.eventSessionId()).isEqualTo(sessionA);
        assertThat(availabilityA.seatStatuses()).hasSize(1);
        assertThat(availabilityA.seatStatuses().getFirst().seatId()).isEqualTo(seat);

        var availabilityB = reservationService.getSeatAvailability(sessionB);
        assertThat(availabilityB.eventSessionId()).isEqualTo(sessionB);
        assertThat(availabilityB.seatStatuses()).isEmpty();
    }

    @Test
    @Order(2)
    void sameSeatCanBeReservedIndependentlyInTwoSessions() {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionA, eventId);
        stubSession(sessionB, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("30.00")));

        var first = reservationService.createReservation(
                request(sessionA, eventId, List.of(seat), List.of(new BigDecimal("30.00")), "idem-2a-" + UUID.randomUUID()), null);
        var second = reservationService.createReservation(
                request(sessionB, eventId, List.of(seat), List.of(new BigDecimal("30.00")), "idem-2b-" + UUID.randomUUID()), null);

        assertThat(first.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(second.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(first.eventSessionId()).isEqualTo(sessionA);
        assertThat(second.eventSessionId()).isEqualTo(sessionB);
        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionA, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isEqualTo(1);
        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionB, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isEqualTo(1);
    }

    @Test
    @Order(3)
    void concurrentSameSessionAttemptsYieldExactlyOneWinner() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionId, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("40.00")));

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<String> errorDetails = new java.util.concurrent.ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    reservationService.createReservation(
                            request(sessionId, eventId, List.of(seat), List.of(new BigDecimal("40.00")),
                                    "idem-3-" + index + "-" + UUID.randomUUID()), null);
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    error.incrementAndGet();
                    errorDetails.add(e.getClass().getName() + ": " + e.getMessage());
                }
                return null;
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(errorDetails).as("unexpected errors: %s", errorDetails).isEmpty();
        assertThat(error.get()).isZero();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionId, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isEqualTo(1);
    }

    @Test
    @Order(4)
    void concurrentSplitAcrossSessionsYieldsOneWinnerPerSession() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionA, eventId);
        stubSession(sessionB, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("40.00")));

        int perSession = 10;
        ExecutorService executor = Executors.newFixedThreadPool(perSession * 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successA = new AtomicInteger();
        AtomicInteger successB = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<String> errorDetails = new java.util.concurrent.ConcurrentLinkedQueue<>();

        for (int i = 0; i < perSession; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    start.await();
                    reservationService.createReservation(
                            request(sessionA, eventId, List.of(seat), List.of(new BigDecimal("40.00")),
                                    "idem-4a-" + index + "-" + UUID.randomUUID()), null);
                    successA.incrementAndGet();
                } catch (ConflictException e) {
                    // expected loser
                } catch (Exception e) {
                    error.incrementAndGet();
                    errorDetails.add("A:" + e.getClass().getName() + ": " + e.getMessage());
                }
                return null;
            });
            executor.submit(() -> {
                try {
                    start.await();
                    reservationService.createReservation(
                            request(sessionB, eventId, List.of(seat), List.of(new BigDecimal("40.00")),
                                    "idem-4b-" + index + "-" + UUID.randomUUID()), null);
                    successB.incrementAndGet();
                } catch (ConflictException e) {
                    // expected loser
                } catch (Exception e) {
                    error.incrementAndGet();
                    errorDetails.add("B:" + e.getClass().getName() + ": " + e.getMessage());
                }
                return null;
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(errorDetails).as("unexpected errors: %s", errorDetails).isEmpty();
        assertThat(error.get()).isZero();
        assertThat(successA.get()).isEqualTo(1);
        assertThat(successB.get()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void cancellingSessionADoesNotReleaseSessionB() {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionA, eventId);
        stubSession(sessionB, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("20.00")));

        var reservationA = reservationService.createReservation(
                request(sessionA, eventId, List.of(seat), List.of(new BigDecimal("20.00")), "idem-5a-" + UUID.randomUUID()),
                UUID.randomUUID());
        reservationService.createReservation(
                request(sessionB, eventId, List.of(seat), List.of(new BigDecimal("20.00")), "idem-5b-" + UUID.randomUUID()),
                UUID.randomUUID());

        reservationService.cancelReservation(reservationA.id(), reservationA.userId(), "guest@seatflow.com");

        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionA, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isZero();
        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionB, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isEqualTo(1);
        assertThat(reservationService.getSeatAvailability(sessionB).seatStatuses()).hasSize(1);
        assertThat(reservationService.getSeatAvailability(sessionA).seatStatuses()).isEmpty();
    }

    @Test
    @Order(6)
    void moreThanTenSeatsStillRejected() {
        List<UUID> seats = new ArrayList<>();
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            seats.add(UUID.randomUUID());
            prices.add(new BigDecimal("10.00"));
        }
        var req = request(UUID.randomUUID(), UUID.randomUUID(), seats, prices, "idem-6-" + UUID.randomUUID());

        assertThatThrownBy(() -> reservationService.createReservation(req, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getErrorCode()).isEqualTo(ErrorCode.MAX_SEATS_EXCEEDED));
    }

    @Test
    @Order(7)
    void holdExpiryRemainsFifteenMinutes() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionId, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("15.00")));

        Instant before = Instant.now();
        var response = reservationService.createReservation(
                request(sessionId, eventId, List.of(seat), List.of(new BigDecimal("15.00")), "idem-7-" + UUID.randomUUID()), null);

        assertThat(response.expiresAt()).isAfter(before.plus(Duration.ofMinutes(14)));
        assertThat(response.expiresAt()).isBefore(before.plus(Duration.ofMinutes(16)));
    }

    @Test
    @Order(8)
    void nonBookableSessionRejectedBeforeInventoryMutation() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionId, eventId, "CANCELLED", "PUBLISHED");

        long holdsBefore = seatHoldRepository.count();
        long outboxBefore = outboxEventRepository.count();

        assertThatThrownBy(() -> reservationService.createReservation(
                request(sessionId, eventId, List.of(seat), List.of(new BigDecimal("15.00")), "idem-8-" + UUID.randomUUID()), null))
                .isInstanceOf(ValidationException.class);

        assertThat(seatHoldRepository.count()).isEqualTo(holdsBefore);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore);
    }

    @Test
    @Order(9)
    void spoofedCompatEventIdRejectedBeforeInventoryMutation() {
        UUID realEventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID spoofedEventId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionId, realEventId);

        long holdsBefore = seatHoldRepository.count();

        assertThatThrownBy(() -> reservationService.createReservation(
                request(sessionId, spoofedEventId, List.of(seat), List.of(new BigDecimal("15.00")), "idem-9-" + UUID.randomUUID()), null))
                .isInstanceOf(ValidationException.class);

        assertThat(seatHoldRepository.count()).isEqualTo(holdsBefore);
    }

    @Test
    @Order(10)
    void outboxPayloadCarriesSessionAndIsAtomicWithReservation() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionId, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("22.00")));

        var response = reservationService.createReservation(
                request(sessionId, eventId, List.of(seat), List.of(new BigDecimal("22.00")), "idem-10-" + UUID.randomUUID()), null);

        var stored = reservationRepository.findWithSeatHoldsById(response.id()).orElseThrow();
        assertThat(stored.getEventSessionId()).isEqualTo(sessionId);
        assertThat(stored.getSeatHolds()).allMatch(h -> sessionId.equals(h.getEventSessionId()));

        var outbox = outboxEventRepository.findAll().stream()
                .filter(o -> response.id().equals(o.getAggregateId())
                        && "ReservationHeldEvent".equals(o.getEventType()))
                .toList();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.getFirst().getPayload()).contains(sessionId.toString());
        assertThat(outbox.getFirst().getPublishedAt()).isNull();
    }

    @Test
    @Order(11)
    void backfillAssignsSessionAndPassesZeroNullGate() {
        UUID legacyEventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        stubSession(sessionId, legacyEventId);

        Reservation legacy = Reservation.builder()
                .eventId(legacyEventId)
                .customerEmail("legacy@seatflow.com")
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey("legacy-" + UUID.randomUUID())
                .totalAmount(new BigDecimal("10.00"))
                .seatCount(1)
                .build();
        SeatHold legacyHold = SeatHold.builder()
                .eventId(legacyEventId)
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("10.00"))
                .build();
        legacy.addSeatHold(legacyHold);
        reservationRepository.saveAndFlush(legacy);

        var result = backfillService.backfill(Map.of(legacyEventId, sessionId));

        assertThat(result.reservationsUpdated()).isEqualTo(1);
        assertThat(result.seatHoldsUpdated()).isEqualTo(1);
        var reloaded = reservationRepository.findWithSeatHoldsById(legacy.getId()).orElseThrow();
        assertThat(reloaded.getEventSessionId()).isEqualTo(sessionId);
        assertThat(reloaded.getSeatHolds()).allMatch(h -> sessionId.equals(h.getEventSessionId()));

        // Rerun is a safe no-op.
        var rerun = backfillService.backfill(Map.of(legacyEventId, sessionId));
        assertThat(rerun.reservationsUpdated()).isZero();
        assertThat(rerun.seatHoldsUpdated()).isZero();
    }

    @Test
    @Order(12)
    void backfillFailsClosedOnMismatchedSession() {
        UUID legacyEventId = UUID.randomUUID();
        UUID foreignEventId = UUID.randomUUID();
        UUID foreignSessionId = UUID.randomUUID();
        stubSession(foreignSessionId, foreignEventId);

        Reservation legacy = Reservation.builder()
                .eventId(legacyEventId)
                .customerEmail("legacy-mismatch@seatflow.com")
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey("legacy-mismatch-" + UUID.randomUUID())
                .totalAmount(new BigDecimal("10.00"))
                .seatCount(1)
                .build();
        legacy.addSeatHold(SeatHold.builder()
                .eventId(legacyEventId)
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("10.00"))
                .build());
        reservationRepository.saveAndFlush(legacy);

        assertThatThrownBy(() -> backfillService.backfill(Map.of(legacyEventId, foreignSessionId)))
                .isInstanceOf(ValidationException.class);

        var reloaded = reservationRepository.findWithSeatHoldsById(legacy.getId()).orElseThrow();
        assertThat(reloaded.getEventSessionId()).isNull();
    }

    @Test
    @Order(13)
    void backfillGateFailsOnUnmappedLegacyEvent() {
        UUID mappedEventId = UUID.randomUUID();
        UUID mappedSessionId = UUID.randomUUID();
        stubSession(mappedSessionId, mappedEventId);

        UUID orphanEventId = UUID.randomUUID();
        Reservation orphan = Reservation.builder()
                .eventId(orphanEventId)
                .customerEmail("orphan@seatflow.com")
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey("orphan-" + UUID.randomUUID())
                .totalAmount(new BigDecimal("10.00"))
                .seatCount(1)
                .build();
        orphan.addSeatHold(SeatHold.builder()
                .eventId(orphanEventId)
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("10.00"))
                .build());
        reservationRepository.saveAndFlush(orphan);

        // The mapped event has no rows; the orphan legacy event is not in the mapping,
        // so the zero-null gate must fail closed instead of guessing.
        assertThatThrownBy(() -> backfillService.backfill(Map.of(mappedEventId, mappedSessionId)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Order(20)
    void expiringSessionADoesNotReleaseSessionB() {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionA, eventId);
        stubSession(sessionB, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("20.00")));

        var reservationA = reservationService.createReservation(
                request(sessionA, eventId, List.of(seat), List.of(new BigDecimal("20.00")), "idem-20a-" + UUID.randomUUID()), null);
        var reservationB = reservationService.createReservation(
                request(sessionB, eventId, List.of(seat), List.of(new BigDecimal("20.00")), "idem-20b-" + UUID.randomUUID()), null);
        reservationService.confirmReservation(reservationB.id(), UUID.randomUUID());

        int processed = reservationService.expireHoldReservations(Instant.now().plus(Duration.ofMinutes(16)), 100);
        assertThat(processed).isGreaterThanOrEqualTo(1);

        var reloadedA = reservationRepository.findWithSeatHoldsById(reservationA.id()).orElseThrow();
        assertThat(reloadedA.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reloadedA.getSeatHolds()).allMatch(h -> h.getStatus() == SeatHoldStatus.RELEASED);

        var reloadedB = reservationRepository.findWithSeatHoldsById(reservationB.id()).orElseThrow();
        assertThat(reloadedB.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reloadedB.getSeatHolds()).allMatch(h -> h.getStatus() == SeatHoldStatus.SOLD);

        var expiredEvents = outboxEventRepository.findAll().stream()
                .filter(o -> reservationA.id().equals(o.getAggregateId())
                        && "ReservationExpiredEvent".equals(o.getEventType()))
                .toList();
        assertThat(expiredEvents).hasSize(1);
        assertThat(expiredEvents.getFirst().getPayload()).contains(sessionA.toString());
    }
}
