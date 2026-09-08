package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code daily_operational_metrics} (TASK-P14-003).
 */
@Repository
public interface DailyOperationalMetricRepository
        extends JpaRepository<DailyOperationalMetric, DailyOperationalMetric.Key> {
}
