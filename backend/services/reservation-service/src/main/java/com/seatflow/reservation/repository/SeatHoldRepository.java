package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

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
}
