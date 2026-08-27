package com.seatflow.ticket.repository;

import com.seatflow.ticket.model.entity.TicketValidation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TicketValidationRepository extends JpaRepository<TicketValidation, UUID> {

    List<TicketValidation> findByTicketIdOrderByScannedAtDesc(UUID ticketId);

    List<TicketValidation> findByScannerDeviceIdOrderByScannedAtDesc(String scannerDeviceId, Pageable pageable);
}
