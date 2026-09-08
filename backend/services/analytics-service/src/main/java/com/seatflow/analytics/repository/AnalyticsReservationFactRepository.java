package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.AnalyticsReservationFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code analytics_reservation_facts} (TASK-P14-003).
 */
@Repository
public interface AnalyticsReservationFactRepository extends JpaRepository<AnalyticsReservationFact, UUID> {

    List<AnalyticsReservationFact> findByEventSessionId(UUID eventSessionId);
}
