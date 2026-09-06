package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.AnalyticsTicketFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code analytics_ticket_facts} (TASK-P14-003).
 */
@Repository
public interface AnalyticsTicketFactRepository extends JpaRepository<AnalyticsTicketFact, UUID> {

    List<AnalyticsTicketFact> findByReservationId(UUID reservationId);

    List<AnalyticsTicketFact> findByEventSessionId(UUID eventSessionId);
}
