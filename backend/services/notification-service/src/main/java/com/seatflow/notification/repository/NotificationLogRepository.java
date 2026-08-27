package com.seatflow.notification.repository;

import com.seatflow.notification.model.entity.NotificationLog;
import com.seatflow.notification.model.enums.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<NotificationLog> findByIdempotencyKey(String idempotencyKey);

    Page<NotificationLog> findByRecipientEmailOrderByCreatedAtDesc(String recipientEmail, Pageable pageable);

    Page<NotificationLog> findByStatusOrderByCreatedAtDesc(NotificationStatus status, Pageable pageable);

    Page<NotificationLog> findByRecipientEmailAndStatusOrderByCreatedAtDesc(
            String recipientEmail, NotificationStatus status, Pageable pageable);

    @Query(value = """
            SELECT * FROM notification_logs
            WHERE status = 'FAILED' AND retry_count < :maxRetries
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :limit
            """, nativeQuery = true)
    List<NotificationLog> findFailedNotificationsForRetry(
            @Param("maxRetries") int maxRetries,
            @Param("limit") int limit);
}
