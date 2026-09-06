package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.EventSessionRevenueMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@code event_session_revenue_metrics} (TASK-P14-003).
 */
@Repository
public interface EventSessionRevenueMetricRepository
        extends JpaRepository<EventSessionRevenueMetric, EventSessionRevenueMetric.Key> {

    List<EventSessionRevenueMetric> findByEventSessionId(UUID eventSessionId);
}
