package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@code daily_revenue_metrics} (TASK-P14-003).
 */
@Repository
public interface DailyRevenueMetricRepository
        extends JpaRepository<DailyRevenueMetric, DailyRevenueMetric.Key> {
}
