package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Persistence for {@code analytics_session_facts} (TASK-P14-003).
 */
@Repository
public interface AnalyticsSessionFactRepository extends JpaRepository<AnalyticsSessionFact, UUID> {
}
