package com.seatflow.reservation.integration;

import com.seatflow.common.domain.exception.ConflictException;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import org.junit.jupiter.api.Test;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P12-008 final verification suite for the reservation leg of the
 * reservation -&gt; payment -&gt; ticket chain (real PostgreSQL via Testcontainers;
 * event-service is stubbed at the client boundary with explicit session times).
 *
 * <p>Covers the gaps left by {@code SessionScopedInventoryIntegrationTest}:
 * <ul>
 *   <li>Scenario A: the hold -&gt; confirm chain carries the exact
 *       {@code eventSessionId} plus the immutable showing snapshot
 *       ({@code sessionStartsAt}/{@code sessionEndsAt}) end to end, and session
 *       B availability stays independent after session A books.</li>
 *   <li>Scenario E: the 15-minute hold boundary with explicit controllable
 *       instants — no early release just before expiry, release just after,
 *       and no cross-session release of a confirmed B reservation.</li>
 *   <li>Scenario F: repeating create with the same idempotency key returns the
 *       identical reservation with no duplicate rows/outbox events, while key
 *       reuse across different seats or sessions is rejected.</li>
 * </ul>
 *
 * <p>No arbitrary sleeps: expiry is driven by explicit {@link Instant}s passed
 * to {@code expireHoldReservations}. No latency thresholds are asserted.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class EventSessionChainExpiryIdempotencyRegressionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_p12_008_test")
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

    private void stubSession(UUID sessionId, UUID eventId, Instant startsAt, Instant endsAt) {
        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                startsAt, endsAt, null, null, UUID.randomUUID()));
    }

    private void stubPricing(UUID eventId, Map<UUID, BigDecimal> prices) {
        List<UUID> seatIds = new ArrayList<>(prices.keySet());
        when(eventClient.getEventSeatPricing(eq(eventId), any())).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", seatIds, prices));
    }

    private CreateReservationRequest request(UUID sessionId, List<UUID> seatIds,
                                             List<BigDecimal> prices, String key) {
        return new CreateReservationRequest(sessionId, "guest@seatflow.com", seatIds, prices, key);
    }

    @Test
    void reservationToConfirmChainCarriesExactSessionSnapshot() {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seatA1 = UUID.randomUUID();
        // Truncated to millis: Jackson renders envelope instants with
        // millisecond precision, so whole-second/millis instants round-trip
        // to identical text while nanos would not.
        Instant startsAt = Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant endsAt = startsAt.plusSeconds(7200);
        stubSession(sessionA, eventId, startsAt, endsAt);
        stubSession(sessionB, eventId, startsAt.plusSeconds(86400), endsAt.plusSeconds(86400));
        stubPricing(eventId, Map.of(seatA1, new BigDecimal("25.00")));

        var held = reservationService.createReservation(
                request(sessionA, List.of(seatA1), List.of(new BigDecimal("25.00")),
                        "p12-008-chain-" + UUID.randomUUID()), null);

        // The hold response carries the exact session identity plus the immutable
        // showing snapshot captured from the trusted booking context.
        assertThat(held.eventSessionId()).isEqualTo(sessionA);
        assertThat(held.eventId()).isEqualTo(eventId);
        assertThat(held.sessionStartsAt()).isEqualTo(startsAt);
        assertThat(held.sessionEndsAt()).isEqualTo(endsAt);

        // Session B availability is independent after the A booking.
        assertThat(reservationService.getSeatAvailability(sessionA).seatStatuses()).hasSize(1);
        assertThat(reservationService.getSeatAvailability(sessionB).seatStatuses()).isEmpty();

        // Confirm (the payment-completed path) forwards the same exact session
        // and snapshot — never the sibling session, never a re-resolved time.
        UUID paymentId = UUID.randomUUID();
        reservationService.confirmReservation(held.id(), paymentId);

        var confirmedEvents = outboxEventRepository.findAll().stream()
                .filter(o -> held.id().equals(o.getAggregateId())
                        && "ReservationConfirmedEvent".equals(o.getEventType()))
                .toList();
        assertThat(confirmedEvents).hasSize(1);
        String payload = confirmedEvents.getFirst().getPayload();
        assertThat(payload).contains(sessionA.toString());
        assertThat(payload).contains(startsAt.toString());
        assertThat(payload).contains(endsAt.toString());
        assertThat(payload).doesNotContain(sessionB.toString());

        Reservation stored = reservationRepository.findWithSeatHoldsById(held.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stored.getEventSessionId()).isEqualTo(sessionA);
        assertThat(stored.getSessionStartsAt()).isEqualTo(startsAt);
        assertThat(stored.getSessionEndsAt()).isEqualTo(endsAt);
    }

    @Test
    void holdExpiryBoundaryReleasesAfterFifteenMinutesOnly() {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        Instant startsAt = Instant.now().plusSeconds(86400);
        stubSession(sessionA, eventId, startsAt, startsAt.plusSeconds(7200));
        stubSession(sessionB, eventId, startsAt.plusSeconds(86400), startsAt.plusSeconds(93600));
        stubPricing(eventId, Map.of(seat, new BigDecimal("20.00")));

        // Tight 15-minute duration oracle: the persisted expiry must equal
        // creation time plus exactly 15 minutes within execution-time bounds
        // (milliseconds) — a 14m30s HOLD_DURATION fails deterministically.
        Instant beforeCreate = Instant.now();
        var reservationA = reservationService.createReservation(
                request(sessionA, List.of(seat), List.of(new BigDecimal("20.00")),
                        "p12-008-expiry-a-" + UUID.randomUUID()), null);
        Instant afterCreate = Instant.now();
        var reservationB = reservationService.createReservation(
                request(sessionB, List.of(seat), List.of(new BigDecimal("20.00")),
                        "p12-008-expiry-b-" + UUID.randomUUID()), null);
        // The B control is confirmed, so it is immune to the hold sweep by
        // status — the sweep must still leave its SOLD holds untouched.
        reservationService.confirmReservation(reservationB.id(), UUID.randomUUID());

        Instant expiresAtA = reservationA.expiresAt();
        assertThat(expiresAtA)
                .isAfterOrEqualTo(beforeCreate.plusSeconds(900))
                .isBeforeOrEqualTo(afterCreate.plusSeconds(900));

        // Just before the 15-minute boundary: A itself is not released (the
        // sweep count is not asserted globally — sibling tests in this class
        // share the database and may own older PENDING rows).
        reservationService.expireHoldReservations(expiresAtA.minusSeconds(1), 100);
        var stillPending = reservationRepository.findWithSeatHoldsById(reservationA.id()).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(stillPending.getSeatHolds()).allMatch(h -> h.getStatus() == SeatHoldStatus.HELD);
        assertThat(outboxEventRepository.countByAggregateIdAndEventType(
                reservationA.id(), "ReservationExpiredEvent")).isZero();

        // Exact-boundary `< now` semantics: sweeping at precisely the stored
        // expiry instant must NOT release (the query is expires_at < now).
        // The stored value is reloaded so DB timestamp precision cannot blur
        // the boundary.
        Instant storedExpiresAt =
                reservationRepository.findWithSeatHoldsById(reservationA.id()).orElseThrow().getExpiresAt();
        reservationService.expireHoldReservations(storedExpiresAt, 100);
        var atBoundary = reservationRepository.findWithSeatHoldsById(reservationA.id()).orElseThrow();
        assertThat(atBoundary.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(atBoundary.getSeatHolds()).allMatch(h -> h.getStatus() == SeatHoldStatus.HELD);
        assertThat(outboxEventRepository.countByAggregateIdAndEventType(
                reservationA.id(), "ReservationExpiredEvent")).isZero();

        // Just after the boundary: A expires with a session-scoped outbox event,
        // while confirmed B keeps its SOLD hold in the same physical seat.
        // (The processed count is not asserted — sibling PENDING rows may
        // expire in the same sweep; per-row state below is the oracle.)
        reservationService.expireHoldReservations(expiresAtA.plusSeconds(1), 100);

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

    @Test
    void idempotencyKeyRepeatReturnsSameReservationWithoutDuplicates() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        Instant startsAt = Instant.now().plusSeconds(86400);
        stubSession(sessionId, eventId, startsAt, startsAt.plusSeconds(7200));
        stubPricing(eventId, Map.of(seat, new BigDecimal("18.00")));

        String idempotencyKey = "p12-008-idem-" + UUID.randomUUID();
        long reservationsBefore = reservationRepository.count();
        long holdsBefore = seatHoldRepository.count();

        var first = reservationService.createReservation(
                request(sessionId, List.of(seat), List.of(new BigDecimal("18.00")), idempotencyKey), null);
        var second = reservationService.createReservation(
                request(sessionId, List.of(seat), List.of(new BigDecimal("18.00")), idempotencyKey), null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.eventSessionId()).isEqualTo(sessionId);
        assertThat(reservationRepository.count()).isEqualTo(reservationsBefore + 1);
        assertThat(seatHoldRepository.count()).isEqualTo(holdsBefore + 1);
        assertThat(outboxEventRepository.countByAggregateIdAndEventType(first.id(), "ReservationHeldEvent"))
                .isEqualTo(1);
    }

    @Test
    void idempotencyKeyReuseAcrossSeatsOrSessionIsRejected() {
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();
        UUID seat2 = UUID.randomUUID();
        Instant startsAt = Instant.now().plusSeconds(86400);
        stubSession(sessionA, eventId, startsAt, startsAt.plusSeconds(7200));
        stubSession(sessionB, eventId, startsAt.plusSeconds(86400), startsAt.plusSeconds(93600));
        stubPricing(eventId, Map.of(seat1, new BigDecimal("18.00"), seat2, new BigDecimal("18.00")));

        String idempotencyKey = "p12-008-idem-cross-" + UUID.randomUUID();
        reservationService.createReservation(
                request(sessionA, List.of(seat1), List.of(new BigDecimal("18.00")), idempotencyKey), null);

        // Same key, different seats: rejected, never silently re-seated.
        assertThatThrownBy(() -> reservationService.createReservation(
                request(sessionA, List.of(seat2), List.of(new BigDecimal("18.00")), idempotencyKey), null))
                .isInstanceOf(ConflictException.class);

        // Same key, different session: rejected, never silently re-scoped.
        assertThatThrownBy(() -> reservationService.createReservation(
                request(sessionB, List.of(seat1), List.of(new BigDecimal("18.00")), idempotencyKey), null))
                .isInstanceOf(ConflictException.class);
    }
}
