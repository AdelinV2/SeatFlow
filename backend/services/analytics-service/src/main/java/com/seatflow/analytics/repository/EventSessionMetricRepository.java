package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.EventSessionMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Persistence for {@code event_session_metrics} (TASK-P14-003).
 */
@Repository
public interface EventSessionMetricRepository extends JpaRepository<EventSessionMetric, UUID> {
}
