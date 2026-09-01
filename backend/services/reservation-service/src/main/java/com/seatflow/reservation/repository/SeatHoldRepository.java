package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.projection.ActiveSeatHoldProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    long countByEventIdAndSeatIdAndStatusIn(UUID eventId, UUID seatId, Collection<SeatHoldStatus> statuses);

    @Query("""
           SELECT sh
           FROM SeatHold sh
           WHERE sh.eventId = :eventId
             AND sh.seatId = :seatId
             AND sh.status IN (com.seatflow.reservation.model.enums.SeatHoldStatus.HELD,
                               com.seatflow.reservation.model.enums.SeatHoldStatus.SOLD)
           """)
    Optional<SeatHold> findActiveHold(@Param("eventId") UUID eventId, @Param("seatId") UUID seatId);

    List<SeatHold> findByEventIdAndSeatIdInAndStatusIn(UUID eventId,
                                                        Collection<UUID> seatIds,
                                                        Collection<SeatHoldStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT sh
           FROM SeatHold sh
           WHERE sh.eventId = :eventId
             AND sh.seatId IN :seatIds
             AND sh.status IN (com.seatflow.reservation.model.enums.SeatHoldStatus.HELD,
                               com.seatflow.reservation.model.enums.SeatHoldStatus.SOLD)
           """)
    List<SeatHold> findAndLockSeatsForUpdate(@Param("eventId") UUID eventId,
                                              @Param("seatIds") Collection<UUID> seatIds);

    @Query("""
           SELECT sh.seatId AS seatId, sh.status AS status
           FROM SeatHold sh
           WHERE sh.eventId = :eventId
             AND sh.status IN (com.seatflow.reservation.model.enums.SeatHoldStatus.HELD,
                               com.seatflow.reservation.model.enums.SeatHoldStatus.SOLD)
           """)
    List<ActiveSeatHoldProjection> findActiveSeatHoldsByEventId(@Param("eventId") UUID eventId);
}
