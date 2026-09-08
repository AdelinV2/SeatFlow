package com.seatflow.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable {@code EventEnvelope.eventId} deduplication boundary for the analytics read model.
 *
 * <p>TASK-P14-002: one row per first-seen canonical event. The claim
 * ({@code INSERT ... ON CONFLICT DO NOTHING}) and all projection mutations for that event
 * share a single local transaction on {@code seatflow_analytics}. A failed projection rolls
 * back the claim so a retry can own the event; a duplicate delivery after commit is a no-op.
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 128)
    @ToString.Include
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 128)
    @ToString.Include
    private String eventType;

    @Column(name = "source_topic", nullable = false, length = 255)
    private String sourceTopic;

    @Column(name = "source_partition")
    private Integer sourcePartition;

    @Column(name = "source_offset")
    private Long sourceOffset;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        ProcessedEvent that = (ProcessedEvent) o;
        return getEventId() != null && Objects.equals(getEventId(), that.getEventId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
