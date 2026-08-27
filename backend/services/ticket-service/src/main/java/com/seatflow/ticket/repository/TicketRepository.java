package com.seatflow.ticket.repository;

import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.model.enums.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByTicketCode(String ticketCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.ticketCode = :ticketCode")
    Optional<Ticket> findByTicketCodeForUpdate(@Param("ticketCode") String ticketCode);

    Optional<Ticket> findByIdAndUserId(UUID id, UUID userId);

    Page<Ticket> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Ticket> findByReservationId(UUID reservationId);

    List<Ticket> findByPaymentId(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);

    boolean existsByEventIdAndSeatIdAndStatus(UUID eventId, UUID seatId, TicketStatus status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.userId = :userId WHERE t.customerEmail = :customerEmail AND t.userId IS NULL")
    int updateUserIdByCustomerEmailAndUserIdIsNull(@Param("userId") UUID userId, @Param("customerEmail") String customerEmail);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Ticket t SET t.userId = :userId WHERE LOWER(t.customerEmail) = LOWER(:customerEmail) AND t.userId IS NULL")
    int updateUserIdByNormalizedEmail(@Param("userId") UUID userId, @Param("customerEmail") String customerEmail);
}
