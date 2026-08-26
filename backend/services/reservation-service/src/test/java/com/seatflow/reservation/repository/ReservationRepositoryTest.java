package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import jakarta.persistence.EntityManager;
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
class ReservationRepositoryTest {

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
    private EntityManager entityManager;

    private Reservation reservation(ReservationStatus status, Instant expiresAt, String idempotencyKey, UUID userId) {
        return Reservation.builder()
                .eventId(UUID.randomUUID())
                .customerEmail("guest@example.com")
                .customerName("Guest User")
                .status(status)
                .expiresAt(expiresAt)
                .idempotencyKey(idempotencyKey)
                .totalAmount(new BigDecimal("100.00"))
                .seatCount(1)
                .userId(userId)
                .build();
    }

    @Test
    void shouldFindByUniqueIdempotencyKey() {
        Reservation saved = reservationRepository.saveAndFlush(
                reservation(ReservationStatus.PENDING, Instant.now().plusSeconds(900), "key-1", null));

        Optional<Reservation> found = reservationRepository.findByIdempotencyKey("key-1");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getSeatCount()).isEqualTo(1);
        assertThat(found.get().getVersion()).isNotNull();
    }

    @Test
    void shouldRejectDuplicateIdempotencyKeyWithConstraintViolation() {
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.PENDING, Instant.now().plusSeconds(900), "dup-key", null));
        Reservation duplicate = reservation(ReservationStatus.PENDING, Instant.now().plusSeconds(900), "dup-key", null);

        assertThatThrownBy(() -> reservationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldLoadSeatHoldsEagerlyWithEntityGraph() {
        Reservation reservation = reservation(ReservationStatus.PENDING, Instant.now().plusSeconds(900), "key-graph", null);
        SeatHold hold = SeatHold.builder()
                .eventId(reservation.getEventId())
                .seatId(UUID.randomUUID())
                .status(SeatHoldStatus.HELD)
                .price(new BigDecimal("50.00"))
                .build();
        reservation.addSeatHold(hold);
        reservationRepository.saveAndFlush(reservation);

        entityManager.clear();

        Reservation loaded = reservationRepository.findWithSeatHoldsById(reservation.getId()).orElseThrow();
        assertThat(loaded.getSeatHolds()).hasSize(1);
        assertThat(loaded.getSeatHolds().iterator().next().getStatus()).isEqualTo(SeatHoldStatus.HELD);
    }

    @Test
    void findExpiredReservationsForUpdateMustReturnOnlyPendingPastDue() {
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.PENDING, Instant.now().minusSeconds(3600), "exp-1", null));
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.CONFIRMED, Instant.now().minusSeconds(3600), "exp-2", null));
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.PENDING, Instant.now().plusSeconds(3600), "exp-3", null));

        entityManager.clear();

        List<UUID> expired = reservationRepository.findExpiredReservationsForUpdate(Instant.now(), 50);

        assertThat(expired).hasSize(1);
        assertThat(reservationRepository.findById(expired.getFirst()).orElseThrow().getIdempotencyKey())
                .isEqualTo("exp-1");
    }

    @Test
    void findExpiredReservationsForUpdateMustHonorLimit() {
        for (int i = 0; i < 5; i++) {
            reservationRepository.saveAndFlush(
                    reservation(ReservationStatus.PENDING, Instant.now().minusSeconds(3600), "limit-" + i, null));
        }

        List<UUID> expired = reservationRepository.findExpiredReservationsForUpdate(Instant.now(), 2);

        assertThat(expired).hasSize(2);
    }

    @Test
    void updateUserIdForGuestEmailMustUpdateOnlyGuestReservations() {
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.PENDING, Instant.now().plusSeconds(900), "guest-1", null));
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.PENDING, Instant.now().plusSeconds(900), "guest-2", UUID.randomUUID()));
        Reservation otherEmail = Reservation.builder()
                .eventId(UUID.randomUUID())
                .customerEmail("other@example.com")
                .customerName("Other User")
                .status(ReservationStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(900))
                .idempotencyKey("other-1")
                .totalAmount(new BigDecimal("100.00"))
                .seatCount(1)
                .build();
        reservationRepository.saveAndFlush(otherEmail);

        UUID newUserId = UUID.randomUUID();
        int updated = reservationRepository.updateUserIdForGuestEmail("guest@example.com", newUserId);

        assertThat(updated).isEqualTo(1);
        Reservation guest = reservationRepository.findByIdempotencyKey("guest-1").orElseThrow();
        assertThat(guest.getUserId()).isEqualTo(newUserId);
        Reservation registered = reservationRepository.findByIdempotencyKey("guest-2").orElseThrow();
        assertThat(registered.getUserId()).isNotNull();
        Reservation other = reservationRepository.findByIdempotencyKey("other-1").orElseThrow();
        assertThat(other.getUserId()).isNull();
    }

    @Test
    void findExpiredPagedMustReturnOnlyPendingPastDue() {
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.PENDING, Instant.now().minusSeconds(3600), "jpql-1", null));
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.CONFIRMED, Instant.now().minusSeconds(3600), "jpql-2", null));
        reservationRepository.saveAndFlush(
                reservation(ReservationStatus.PENDING, Instant.now().plusSeconds(3600), "jpql-3", null));

        entityManager.clear();

        var expired = reservationRepository.findExpiredPaged(Instant.now(), org.springframework.data.domain.Pageable.ofSize(50));

        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().getIdempotencyKey()).isEqualTo("jpql-1");
    }
}
