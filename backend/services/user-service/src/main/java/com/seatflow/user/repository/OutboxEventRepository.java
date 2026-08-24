package com.seatflow.user.repository;

import com.seatflow.user.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

    /**
     * Claims a batch of unpublished, not-yet-exhausted events using a row-level lock that
     * skips rows already locked by another publisher instance (PostgreSQL {@code FOR UPDATE SKIP LOCKED}).
     * Events at the retry ceiling are excluded so they are parked instead of being polled forever.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL AND retry_count < :maxRetry
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdate(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.publishedAt = :now WHERE o.id = :id AND o.publishedAt IS NULL")
    int markPublished(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.retryCount = o.retryCount + 1 WHERE o.id = :id AND o.retryCount < :max")
    int incrementRetryCount(@Param("id") UUID id, @Param("max") int max);
}
