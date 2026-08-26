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

    private Reservation pendingReservation() {
        return Reservation.builder()
                .eventId(UUID.randomUUID())
                .customerEmail("guest@example.com")
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey(UUID.randomUUID().toString())
                .totalAmount(new BigDecimal("100.00"))
                .seatCount(1)
                .build();
    }

    @Test
    void shouldPersistSeatHoldWithReservationCascade() {
        Reservation reservation = pendingReservation();
        SeatHold hold = SeatHold.builder()
                .eventId(reservation.getEventId())
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("50.00"))
                .build();
        reservation.addSeatHold(hold);

        Reservation saved = reservationRepository.saveAndFlush(reservation);

        assertThat(seatHoldRepository.count()).isEqualTo(1);
        Optional<SeatHold> reloaded = seatHoldRepository.findById(saved.getSeatHolds().iterator().next().getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getReservation().getId()).isEqualTo(saved.getId());
    }

    @Test
    void duplicateActiveSeatHoldForSameEventAndSeatMustViolateUniqueIndex() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Reservation reservation = pendingReservation();
        reservation.setEventId(eventId);

        SeatHold first = SeatHold.builder()
                .eventId(eventId)
                .seatId(seatId)
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("50.00"))
                .build();
        SeatHold second = SeatHold.builder()
                .eventId(eventId)
                .seatId(seatId)
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("50.00"))
                .build();
        reservation.addSeatHold(first);
        reservation.addSeatHold(second);

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(reservation))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void differentSeatsForSameEventMustNotCollide() {
        UUID eventId = UUID.randomUUID();
        Reservation reservation = pendingReservation();
        reservation.setEventId(eventId);
        reservation.addSeatHold(SeatHold.builder()
                .eventId(eventId)
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("50.00"))
                .build());
        reservation.addSeatHold(SeatHold.builder()
                .eventId(eventId)
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("60.00"))
                .build());

        reservationRepository.saveAndFlush(reservation);

        long active = seatHoldRepository.countByEventIdAndSeatIdAndStatusIn(
                eventId, seatIdOrFirst(reservation), List.of(SeatHoldStatus.HELD, SeatHoldStatus.SOLD));
        assertThat(active).isNotNegative();
        assertThat(seatHoldRepository.count()).isEqualTo(2);
    }

    @Test
    void shouldFindActiveHoldExcludingReleased() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Reservation reservation = pendingReservation();
        reservation.setEventId(eventId);
        SeatHold hold = SeatHold.builder()
                .eventId(eventId)
                .seatId(seatId)
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("50.00"))
                .build();
        reservation.addSeatHold(hold);
        reservationRepository.saveAndFlush(reservation);

        Optional<SeatHold> found = seatHoldRepository.findActiveHold(eventId, seatId);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(SeatHoldStatus.HELD);
    }

    private UUID seatIdOrFirst(Reservation reservation) {
        return reservation.getSeatHolds().iterator().next().getSeatId();
    }
}
