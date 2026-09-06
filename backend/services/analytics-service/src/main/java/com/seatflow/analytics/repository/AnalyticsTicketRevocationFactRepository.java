package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.AnalyticsTicketRevocationFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Persistence for {@code analytics_ticket_revocation_facts} (TASK-P14-003, V2).
 */
@Repository
public interface AnalyticsTicketRevocationFactRepository
        extends JpaRepository<AnalyticsTicketRevocationFact, UUID> {
}
