package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.projection.ActiveSeatHoldProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    long countByStatus(SeatHoldStatus status);

    long countByEventSessionIdAndSeatIdAndStatusIn(UUID eventSessionId, UUID seatId, Collection<SeatHoldStatus> statuses);

    @Query("""
           SELECT sh
           FROM SeatHold sh
           WHERE sh.eventSessionId = :eventSessionId
             AND sh.seatId = :seatId
             AND sh.status IN (com.seatflow.reservation.model.enums.SeatHoldStatus.HELD,
                               com.seatflow.reservation.model.enums.SeatHoldStatus.SOLD)
           """)
    Optional<SeatHold> findActiveHold(@Param("eventSessionId") UUID eventSessionId, @Param("seatId") UUID seatId);

    List<SeatHold> findByEventSessionIdAndSeatIdInAndStatusIn(UUID eventSessionId,
                                                             Collection<UUID> seatIds,
                                                             Collection<SeatHoldStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT sh
           FROM SeatHold sh
           WHERE sh.eventSessionId = :eventSessionId
             AND sh.seatId IN :seatIds
             AND sh.status IN (com.seatflow.reservation.model.enums.SeatHoldStatus.HELD,
                               com.seatflow.reservation.model.enums.SeatHoldStatus.SOLD)
           """)
    List<SeatHold> findAndLockSeatsForUpdate(@Param("eventSessionId") UUID eventSessionId,
                                              @Param("seatIds") Collection<UUID> seatIds);

    @Query("""
           SELECT sh.eventSessionId AS eventSessionId, sh.eventId AS eventId, sh.seatId AS seatId, sh.status AS status
           FROM SeatHold sh
           WHERE sh.eventSessionId = :eventSessionId
             AND sh.status IN (com.seatflow.reservation.model.enums.SeatHoldStatus.HELD,
                               com.seatflow.reservation.model.enums.SeatHoldStatus.SOLD)
           """)
    List<ActiveSeatHoldProjection> findActiveSeatHoldsByEventSessionId(@Param("eventSessionId") UUID eventSessionId);

    // --- P12-003 backfill support (SessionInventoryBackfillService only) ---

    @Query(value = """
            SELECT COUNT(*) FROM seat_holds WHERE event_session_id IS NULL
            """, nativeQuery = true)
    long countSeatHoldsWithNullSession();

    @Query(value = """
            SELECT DISTINCT event_id FROM seat_holds WHERE event_session_id IS NULL
            """, nativeQuery = true)
    List<UUID> findLegacyEventIdsWithNullSession();

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE seat_holds
            SET event_session_id = :eventSessionId
            WHERE event_id = :eventId
              AND event_session_id IS NULL
            """, nativeQuery = true)
    int backfillSessionIdForLegacyEvent(@Param("eventId") UUID eventId,
                                        @Param("eventSessionId") UUID eventSessionId);
}
