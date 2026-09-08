package com.seatflow.analytics.repository;

import com.seatflow.analytics.model.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Durable {@code EventEnvelope.eventId} claim boundary.
 *
 * <p>The single-statement {@code INSERT ... ON CONFLICT DO NOTHING} is the only safe claim:
 * it is atomic under concurrent delivery, unlike {@code existsById()} then {@code save()}.
 * Returns {@code 1} when this caller owns the event, {@code 0} for a duplicate.
 *
 * <p>Must be invoked inside the same local transaction as the projection handler so a
 * handler failure rolls back the claim.
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Query(value = """
            INSERT INTO processed_events
              (event_id, event_type, source_topic, source_partition, source_offset, occurred_at)
            VALUES
              (:eventId, :eventType, :sourceTopic, :sourcePartition, :sourceOffset, :occurredAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("eventId") String eventId,
              @Param("eventType") String eventType,
              @Param("sourceTopic") String sourceTopic,
              @Param("sourcePartition") Integer sourcePartition,
              @Param("sourceOffset") Long sourceOffset,
              @Param("occurredAt") Instant occurredAt);
}
