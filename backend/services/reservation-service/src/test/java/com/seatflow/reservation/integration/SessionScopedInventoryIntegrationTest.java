package com.seatflow.reservation.integration;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.common.domain.exception.ValidationException;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
import com.seatflow.reservation.messaging.event.ReservationHeldEvent;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * P12-003 concurrency oracle + invariant suite (real PostgreSQL via Testcontainers;
 * event-service is stubbed at the client boundary — mocks are insufficient for
 * locking, but sufficient for the trusted booking-context input).
 *
 * <p>The expiry sweep test runs last: it expires every PENDING row on purpose.
 *
 * <p>P12-009: legacy-NULL backfill coverage lives in
 * {@code SessionInventoryBackfillStagedSchemaTest} on a staged pre-constraint
 * schema (V9 NOT NULL rejects NULL inserts here).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionScopedInventoryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SessionScopedInventoryIntegrationTest.class);

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

    // Spy (not mock) on the concrete ObjectMapper: every serialization
    // delegates to the real mapper; order 14 adds a temporary gating answer
    // that is removed afterwards, so later ordered tests run unstubbed. (A
    // repository-interface spy cannot use callRealMethod, so the gate sits on
    // the outbox serialization step inside the same production transaction.)
    @MockitoSpyBean
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private com.seatflow.reservation.messaging.producer.OutboxEventPublisher outboxEventPublisher;

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
                eventId, "PUBLISHED", seatIds, prices));
    }

    private CreateReservationRequest request(UUID sessionId, UUID ignoredLegacyEventId, List<UUID> seatIds,
                                             List<BigDecimal> prices, String key) {
        // P12-007: client request carries session only; parent event derives server-side.
        return new CreateReservationRequest(sessionId, "guest@seatflow.com", seatIds, prices, key);
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
        // Ready barrier + start gate: every contender must be poised on the
        // gate before release, so the run cannot pass with sequential
        // scheduling. ready.countDown() immediately precedes start.await() in
        // each worker; the main thread releases only after all 20 counted down.
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<String> errorDetails = new java.util.concurrent.ConcurrentLinkedQueue<>();
        // P12-008 scenario C evidence: per-attempt latency histogram (log only,
        // no hardware thresholds — the repo defines none).
        java.util.concurrent.ConcurrentLinkedQueue<Long> latenciesNanos = new java.util.concurrent.ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    long attemptStart = System.nanoTime();
                    try {
                        reservationService.createReservation(
                                request(sessionId, eventId, List.of(seat), List.of(new BigDecimal("40.00")),
                                        "idem-3-" + index + "-" + UUID.randomUUID()), null);
                        success.incrementAndGet();
                    } finally {
                        latenciesNanos.add(System.nanoTime() - attemptStart);
                    }
                } catch (ConflictException e) {
                    // Exact loser contract: same-seat contention must surface
                    // the established seat-taken code, not any other conflict.
                    if (e.getErrorCode() == ErrorCode.SEAT_ALREADY_RESERVED) {
                        conflict.incrementAndGet();
                    } else {
                        error.incrementAndGet();
                        errorDetails.add("wrong-code:" + e.getErrorCode() + ": " + e.getMessage());
                    }
                } catch (Exception e) {
                    error.incrementAndGet();
                    errorDetails.add(e.getClass().getName() + ": " + e.getMessage());
                }
                return null;
            });
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).as("all 20 contenders poised").isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<Long> percentiles = percentilesMillis(latenciesNanos);
        log.info("P12-008 scenario C same-session contention evidence. threads={}, winners={}, conflicts={}, latencyMs[p50/p95/p99]={}",
                threads, success.get(), conflict.get(), percentiles);
        writeLatencyEvidence("scenario-C-same-session",
                threads, success.get(), conflict.get(), percentiles);

        assertThat(latenciesNanos).as("every contender recorded an attempt").hasSize(threads);
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
        // Ready barrier + start gate across BOTH sessions: all 20 contenders
        // must be poised before release, or the run proves nothing about
        // partitioned locking under overlap.
        CountDownLatch ready = new CountDownLatch(perSession * 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successA = new AtomicInteger();
        AtomicInteger successB = new AtomicInteger();
        AtomicInteger conflictA = new AtomicInteger();
        AtomicInteger conflictB = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<String> errorDetails = new java.util.concurrent.ConcurrentLinkedQueue<>();
        // P12-008 scenario D evidence: per-session latency histograms (log only,
        // no hardware thresholds). Partitioned session locks must let both
        // sessions elect a winner instead of one global lock serializing them.
        java.util.concurrent.ConcurrentLinkedQueue<Long> latenciesANanos = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.ConcurrentLinkedQueue<Long> latenciesBNanos = new java.util.concurrent.ConcurrentLinkedQueue<>();

        for (int i = 0; i < perSession; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    long attemptStart = System.nanoTime();
                    try {
                        reservationService.createReservation(
                                request(sessionA, eventId, List.of(seat), List.of(new BigDecimal("40.00")),
                                        "idem-4a-" + index + "-" + UUID.randomUUID()), null);
                        successA.incrementAndGet();
                    } finally {
                        latenciesANanos.add(System.nanoTime() - attemptStart);
                    }
                } catch (ConflictException e) {
                    // Exact loser contract per session.
                    if (e.getErrorCode() == ErrorCode.SEAT_ALREADY_RESERVED) {
                        conflictA.incrementAndGet();
                    } else {
                        error.incrementAndGet();
                        errorDetails.add("A:wrong-code:" + e.getErrorCode() + ": " + e.getMessage());
                    }
                } catch (Exception e) {
                    error.incrementAndGet();
                    errorDetails.add("A:" + e.getClass().getName() + ": " + e.getMessage());
                }
                return null;
            });
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    long attemptStart = System.nanoTime();
                    try {
                        reservationService.createReservation(
                                request(sessionB, eventId, List.of(seat), List.of(new BigDecimal("40.00")),
                                        "idem-4b-" + index + "-" + UUID.randomUUID()), null);
                        successB.incrementAndGet();
                    } finally {
                        latenciesBNanos.add(System.nanoTime() - attemptStart);
                    }
                } catch (ConflictException e) {
                    // Exact loser contract per session.
                    if (e.getErrorCode() == ErrorCode.SEAT_ALREADY_RESERVED) {
                        conflictB.incrementAndGet();
                    } else {
                        error.incrementAndGet();
                        errorDetails.add("B:wrong-code:" + e.getErrorCode() + ": " + e.getMessage());
                    }
                } catch (Exception e) {
                    error.incrementAndGet();
                    errorDetails.add("B:" + e.getClass().getName() + ": " + e.getMessage());
                }
                return null;
            });
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).as("all 20 contenders poised").isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        List<Long> percentilesA = percentilesMillis(latenciesANanos);
        List<Long> percentilesB = percentilesMillis(latenciesBNanos);
        log.info("P12-008 scenario D parallel-session evidence. perSession={}, winnersA={}, winnersB={},"
                        + " latencyMsA[p50/p95/p99]={}, latencyMsB[p50/p95/p99]={}",
                perSession, successA.get(), successB.get(), percentilesA, percentilesB);
        // P12-008 REV-004: persist BOTH sessions' percentiles (earlier code
        // wrote only session A even though both were logged).
        writeLatencyEvidenceTwoSessions("scenario-D-parallel-sessions",
                perSession, successA.get(), successB.get(),
                conflictA.get() + conflictB.get(), percentilesA, percentilesB);

        assertThat(latenciesANanos).as("every session-A contender recorded an attempt").hasSize(perSession);
        assertThat(latenciesBNanos).as("every session-B contender recorded an attempt").hasSize(perSession);
        assertThat(errorDetails).as("unexpected errors: %s", errorDetails).isEmpty();
        assertThat(error.get()).isZero();
        assertThat(successA.get()).isEqualTo(1);
        assertThat(successB.get()).isEqualTo(1);
        assertThat(conflictA.get()).isEqualTo(perSession - 1);
        assertThat(conflictB.get()).isEqualTo(perSession - 1);
        // Locks are partitioned by session: each session independently holds the
        // shared physical seat exactly once at the DB level.
        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionA, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isEqualTo(1);
        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionB, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isEqualTo(1);
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

        // Tight execution bounds (milliseconds, not a minute-wide window): a
        // non-15-minute HOLD_DURATION fails deterministically.
        Instant before = Instant.now();
        var response = reservationService.createReservation(
                request(sessionId, eventId, List.of(seat), List.of(new BigDecimal("15.00")), "idem-7-" + UUID.randomUUID()), null);
        Instant after = Instant.now();

        assertThat(response.expiresAt())
                .isAfterOrEqualTo(before.plus(Duration.ofMinutes(15)))
                .isBeforeOrEqualTo(after.plus(Duration.ofMinutes(15)));
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

    // P12-009: legacy-NULL backfill coverage (former orders 11-13) moved to
    // SessionInventoryBackfillStagedSchemaTest on a staged pre-constraint schema:
    // V9 NOT NULL rejects those JPA NULL inserts on the live schema. The
    // live-schema concurrency oracle below keeps its single-database fidelity.

    @Test
    @Order(14)
    void sessionBCompletesWhileSessionACreateReservationIsInFlight() throws Exception {
        // P12-008 scenario D partition oracle (REV-004): a genuinely in-flight
        // session-A createReservation SERVICE transaction is held open AFTER
        // its seat-lock acquisition + aggregate insert and BEFORE commit, by
        // gating the outbox serialization step inside the production
        // transaction (ReservationServiceImpl.saveOutboxRecord runs between
        // saveAndFlush and commit). Session B must then complete within a
        // bounded Future.get while A is still blocked. A regression adding a
        // global event/advisory lock anywhere in the creation path before
        // commit would be held by A's open transaction and block B, failing
        // the bounded get deterministically instead of hanging. Coordination
        // uses latches only — no hardware thresholds, no arbitrary sleeps.
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionA, eventId);
        stubSession(sessionB, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("40.00")));

        CountDownLatch enteredTx = new CountDownLatch(1);
        CountDownLatch releaseTx = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            // Gate ONLY session A's ReservationHeld envelope serialization: at
            // this point A's transaction has already run
            // findAndLockSeatsForUpdate plus the reservation/hold insert, and
            // still holds the transaction open. Session B's own envelope (a
            // different session payload) serializes straight through.
            if (value instanceof EventEnvelope<?> envelope
                    && "ReservationHeldEvent".equals(envelope.eventType())
                    && envelope.payload() instanceof ReservationHeldEvent held
                    && sessionA.equals(held.eventSessionId())) {
                enteredTx.countDown();
                assertThat(releaseTx.await(30, TimeUnit.SECONDS))
                        .as("release signal never arrived; failing instead of hanging")
                        .isTrue();
            }
            return invocation.callRealMethod();
        }).when(objectMapper).writeValueAsString(any());

        ExecutorService booking = Executors.newFixedThreadPool(2);
        try {
            Future<ReservationResponse> inFlightA = booking.submit(() -> reservationService.createReservation(
                    request(sessionA, eventId, List.of(seat), List.of(new BigDecimal("40.00")),
                            "idem-14a-" + UUID.randomUUID()), null));

            assertThat(enteredTx.await(30, TimeUnit.SECONDS))
                    .as("session-A service transaction reached the pre-commit gate")
                    .isTrue();

            Future<ReservationResponse> bookedB = booking.submit(() -> reservationService.createReservation(
                    request(sessionB, eventId, List.of(seat), List.of(new BigDecimal("40.00")),
                            "idem-14b-" + UUID.randomUUID()), null));
            ReservationResponse responseB = bookedB.get(30, TimeUnit.SECONDS);
            assertThat(responseB.eventSessionId()).isEqualTo(sessionB);

            // B provably overtook A: A must still be blocked pre-commit here.
            assertThat(inFlightA.isDone())
                    .as("session-A must still be blocked pre-commit after B completed")
                    .isFalse();

            releaseTx.countDown();
            ReservationResponse responseA = inFlightA.get(30, TimeUnit.SECONDS);
            assertThat(responseA.eventSessionId()).isEqualTo(sessionA);
        } finally {
            releaseTx.countDown();
            booking.shutdownNow();
            // Remove the gating answer so later ordered tests run unstubbed
            // (the gate only matches this test's session-A payload anyway).
            reset(objectMapper);
        }

        // Both sessions independently hold the shared physical seat at the DB level.
        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionA, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isEqualTo(1);
        assertThat(seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionB, seat, List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD))).isEqualTo(1);
    }

    @Test
    @Order(15)
    void brokerPublishFailureLeavesCommittedOutboxRetryableAndAggregateIntact() {
        // P12-008 outbox failure path (publisher leg): the aggregate + outbox
        // commit together, and a broker failure must leave the committed
        // outbox row unpublished/retryable without touching the aggregate.
        // (The publisher never writes aggregates by construction; the rollback
        // leg — forced outbox-write failure leaves no rows — lives in
        // ReservationOutboxFailurePathIntegrationTest.)
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        stubSession(sessionId, eventId);
        stubPricing(eventId, Map.of(seat, new BigDecimal("22.00")));

        var response = reservationService.createReservation(
                request(sessionId, eventId, List.of(seat), List.of(new BigDecimal("22.00")),
                        "idem-15-" + UUID.randomUUID()), null);

        var outboxBefore = outboxEventRepository.findAll().stream()
                .filter(o -> response.id().equals(o.getAggregateId())
                        && "ReservationHeldEvent".equals(o.getEventType()))
                .toList();
        assertThat(outboxBefore).hasSize(1);
        int retryBefore = outboxBefore.getFirst().getRetryCount();

        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedFuture(new RuntimeException("forced broker failure")));

        outboxEventPublisher.publishPendingEvents();

        var outboxAfter = outboxEventRepository.findAll().stream()
                .filter(o -> response.id().equals(o.getAggregateId())
                        && "ReservationHeldEvent".equals(o.getEventType()))
                .toList();
        assertThat(outboxAfter).hasSize(1);
        assertThat(outboxAfter.getFirst().getPublishedAt()).isNull();
        assertThat(outboxAfter.getFirst().getRetryCount()).isGreaterThan(retryBefore);

        var stored = reservationRepository.findWithSeatHoldsById(response.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(stored.getEventSessionId()).isEqualTo(sessionId);
        assertThat(stored.getSeatHolds())
                .allMatch(h -> h.getStatus() == SeatHoldStatus.HELD && sessionId.equals(h.getEventSessionId()));
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

    /**
     * Evidence-only latency summary (P12-008 scenarios C/D). Returns
     * {@code [p50, p95, p99]} in milliseconds. Never used as a pass/fail gate:
     * the repository defines no hardware latency thresholds.
     */    /**
     * Persists the p50/p95/p99 evidence to a reviewable artifact under
     * {@code target/p12-008-latency-evidence/} in addition to the log line
     * (Surefire log retention is not relied upon). Assertion-free: file output
     * never gates the test.
     */
    private static void writeLatencyEvidence(String scenario, int threads, int winners,
                                             int conflicts, List<Long> percentilesMs) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("target", "p12-008-latency-evidence");
            java.nio.file.Files.createDirectories(dir);
            String body = "scenario=" + scenario + System.lineSeparator()
                    + "threads=" + threads + System.lineSeparator()
                    + "winners=" + winners + System.lineSeparator()
                    + "conflicts=" + conflicts + System.lineSeparator()
                    + "latencyMs[p50/p95/p99]=" + percentilesMs + System.lineSeparator()
                    + "recordedAt=" + Instant.now() + System.lineSeparator();
            java.nio.file.Files.writeString(dir.resolve(scenario + ".txt"), body,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not persist latency evidence for " + scenario, e);
        }
    }

    private static <T> java.util.concurrent.CompletableFuture<T> failedFuture(Throwable ex) {
        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }

    /**
     * Two-session variant of {@link #writeLatencyEvidence}: persists per-session
     * p50/p95/p99 for parallel-session evidence (P12-008 scenario D) so review
     * can inspect both sessions, not just session A. Assertion-free like the
     * single-session variant.
     */
    private static void writeLatencyEvidenceTwoSessions(String scenario, int perSession,
                                                        int winnersA, int winnersB, int conflicts,
                                                        List<Long> percentilesAMs, List<Long> percentilesBMs) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("target", "p12-008-latency-evidence");
            java.nio.file.Files.createDirectories(dir);
            String body = "scenario=" + scenario + System.lineSeparator()
                    + "perSession=" + perSession + System.lineSeparator()
                    + "winnersA=" + winnersA + System.lineSeparator()
                    + "winnersB=" + winnersB + System.lineSeparator()
                    + "conflicts=" + conflicts + System.lineSeparator()
                    + "latencyMsA[p50/p95/p99]=" + percentilesAMs + System.lineSeparator()
                    + "latencyMsB[p50/p95/p99]=" + percentilesBMs + System.lineSeparator()
                    + "recordedAt=" + Instant.now() + System.lineSeparator();
            java.nio.file.Files.writeString(dir.resolve(scenario + ".txt"), body,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not persist latency evidence for " + scenario, e);
        }
    }

    private static List<Long> percentilesMillis(java.util.Collection<Long> latenciesNanos) {
        if (latenciesNanos == null || latenciesNanos.isEmpty()) {
            return List.of(-1L, -1L, -1L);
        }
        List<Long> sortedMillis = latenciesNanos.stream()
                .map(nanos -> nanos / 1_000_000L)
                .sorted()
                .toList();
        return List.of(
                sortedMillis.get((int) (0.50 * (sortedMillis.size() - 1))),
                sortedMillis.get((int) (0.95 * (sortedMillis.size() - 1))),
                sortedMillis.get(sortedMillis.size() - 1));
    }
}
