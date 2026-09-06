package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.AnalyticsPaymentFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code analytics_payment_facts} (TASK-P14-003).
 */
@Repository
public interface AnalyticsPaymentFactRepository extends JpaRepository<AnalyticsPaymentFact, UUID> {

    List<AnalyticsPaymentFact> findByReservationId(UUID reservationId);

    List<AnalyticsPaymentFact> findByEventSessionId(UUID eventSessionId);
}
