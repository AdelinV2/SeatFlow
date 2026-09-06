package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@ActiveProfiles("test")
class SeatHoldRepositoryTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    private Reservation pendingReservation(UUID sessionId) {
        return Reservation.builder()
                .eventSessionId(sessionId)
                .eventId(UUID.randomUUID())
                .customerEmail("guest@example.com")
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey(UUID.randomUUID().toString())
                .totalAmount(new BigDecimal("100.00"))
                .seatCount(1)
                .build();
    }

    private SeatHold hold(UUID sessionId, UUID seatId, SeatHoldStatus status) {
        return SeatHold.builder()
                .eventSessionId(sessionId)
                .eventId(UUID.randomUUID())
                .seatId(seatId)
                .status(status)
                .price(new BigDecimal("50.00"))
                .build();
    }

    @Test
    void shouldPersistSeatHoldWithReservationCascade() {
        Reservation reservation = pendingReservation(UUID.randomUUID());
        SeatHold hold = hold(reservation.getEventSessionId(), UUID.randomUUID(), SeatHoldStatus.HELD);
        reservation.addSeatHold(hold);

        Reservation saved = reservationRepository.saveAndFlush(reservation);

        assertThat(seatHoldRepository.count()).isEqualTo(1);
        Optional<SeatHold> reloaded = seatHoldRepository.findById(saved.getSeatHolds().iterator().next().getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getReservation().getId()).isEqualTo(saved.getId());
    }

    @Test
    void duplicateActiveSeatHoldForSameSessionAndSeatMustViolateUniqueIndex() {
        UUID sessionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Reservation reservation = pendingReservation(sessionId);

        reservation.addSeatHold(hold(sessionId, seatId, SeatHoldStatus.HELD));
        reservation.addSeatHold(hold(sessionId, seatId, SeatHoldStatus.HELD));

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(reservation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameSeatInDifferentSessionsMustNotCollide() {
        UUID seatId = UUID.randomUUID();
        Reservation first = pendingReservation(UUID.randomUUID());
        first.addSeatHold(hold(first.getEventSessionId(), seatId, SeatHoldStatus.HELD));
        Reservation second = pendingReservation(UUID.randomUUID());
        second.addSeatHold(hold(second.getEventSessionId(), seatId, SeatHoldStatus.HELD));

        reservationRepository.saveAndFlush(first);
        reservationRepository.saveAndFlush(second);

        assertThat(seatHoldRepository.count()).isEqualTo(2);
    }

    @Test
    void differentSeatsForSameSessionMustNotCollide() {
        UUID sessionId = UUID.randomUUID();
        Reservation reservation = pendingReservation(sessionId);
        reservation.addSeatHold(hold(sessionId, UUID.randomUUID(), SeatHoldStatus.HELD));
        reservation.addSeatHold(hold(sessionId, UUID.randomUUID(), SeatHoldStatus.HELD));

        reservationRepository.saveAndFlush(reservation);

        long active = seatHoldRepository.countByEventSessionIdAndSeatIdAndStatusIn(
                sessionId, seatIdOrFirst(reservation), List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD));
        assertThat(active).isNotNegative();
        assertThat(seatHoldRepository.count()).isEqualTo(2);
    }

    @Test
    void shouldFindActiveHoldExcludingReleased() {
        UUID sessionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Reservation reservation = pendingReservation(sessionId);
        reservation.addSeatHold(hold(sessionId, seatId, SeatHoldStatus.HELD));
        reservationRepository.saveAndFlush(reservation);

        Optional<SeatHold> found = seatHoldRepository.findActiveHold(sessionId, seatId);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(SeatHoldStatus.HELD);
    }

    @Test
    void activeHoldLookupMustBeSessionScoped() {
        UUID seatId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        Reservation reservationA = pendingReservation(sessionA);
        reservationA.addSeatHold(hold(sessionA, seatId, SeatHoldStatus.HELD));
        reservationRepository.saveAndFlush(reservationA);

        assertThat(seatHoldRepository.findActiveHold(sessionA, seatId)).isPresent();
        assertThat(seatHoldRepository.findActiveHold(sessionB, seatId)).isEmpty();
    }

    private UUID seatIdOrFirst(Reservation reservation) {
        return reservation.getSeatHolds().iterator().next().getSeatId();
    }
}
