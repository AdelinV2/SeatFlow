package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.repository.projection.SeatHoldProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = "seatHolds")
    Optional<Reservation> findWithSeatHoldsById(UUID id);

    @EntityGraph(attributePaths = "seatHolds")
    Optional<Reservation> findWithSeatHoldsByIdempotencyKey(String idempotencyKey);

    @Query(value = """
            SELECT r FROM Reservation r
            WHERE r.status = com.seatflow.reservation.model.enums.ReservationStatus.PENDING
              AND r.expiresAt < :now
            ORDER BY r.expiresAt ASC
            """)
    List<Reservation> findExpiredPaged(@Param("now") Instant now, Pageable pageable);

    @Query(value = """
            SELECT r FROM Reservation r
            WHERE r.status = com.seatflow.reservation.model.enums.ReservationStatus.PENDING
              AND r.expiresAt < :now
            ORDER BY r.expiresAt ASC
            """,
            countQuery = """
            SELECT COUNT(r) FROM Reservation r
            WHERE r.status = com.seatflow.reservation.model.enums.ReservationStatus.PENDING
              AND r.expiresAt < :now
            """)
    Page<Reservation> findExpiredPage(@Param("now") Instant now, Pageable pageable);

    @Query(value = """
            SELECT r.id AS id
            FROM reservations r
            WHERE r.status = 'PENDING'
              AND r.expires_at < :now
            ORDER BY r.expires_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
            nativeQuery = true)
    List<UUID> findExpiredReservationsForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            SELECT r.id AS id
            FROM reservations r
            WHERE r.status = 'PENDING'
              AND r.expires_at < :now
            ORDER BY r.expires_at ASC
            FOR UPDATE SKIP LOCKED
            """,
            nativeQuery = true)
    List<UUID> findAllExpiredReservationsForUpdate(@Param("now") Instant now);

    @Query(value = """
            SELECT sh.id AS id, sh.seatId AS seatId, sh.status AS status, sh.price AS price
            FROM seat_holds sh
            WHERE sh.reservation_id = :reservationId
            """,
            nativeQuery = true)
    List<SeatHoldProjection> findSeatHoldProjections(@Param("reservationId") UUID reservationId);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE reservations
            SET status = :newStatus, updated_at = now()
            WHERE id = :id AND status = :expectedStatus
            """,
            nativeQuery = true)
    int markStatus(@Param("id") UUID id,
                   @Param("expectedStatus") ReservationStatus expectedStatus,
                   @Param("newStatus") ReservationStatus newStatus);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE seat_holds
            SET status = :newStatus, reservation_id = :toReservationId
            WHERE reservation_id = :fromReservationId
              AND status = :expectedStatus
            """,
            nativeQuery = true)
    int moveActiveSeatHolds(@Param("fromReservationId") UUID fromReservationId,
                            @Param("toReservationId") UUID toReservationId,
                            @Param("expectedStatus") com.seatflow.reservation.model.enums.SeatHoldStatus expectedStatus,
                            @Param("newStatus") com.seatflow.reservation.model.enums.SeatHoldStatus newStatus);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE reservations r
            SET user_id = :userId,
                updated_at = now()
            WHERE r.customer_email = :customerEmail
              AND r.user_id IS NULL
            """,
            nativeQuery = true)
    int updateUserIdForGuestEmail(@Param("customerEmail") String customerEmail, @Param("userId") UUID userId);
}
