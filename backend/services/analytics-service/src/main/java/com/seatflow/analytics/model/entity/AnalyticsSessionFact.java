package com.seatflow.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Analytics display snapshot for one event session, populated only from events.
 *
 * <p>TASK-P14-003 fact. Rows are correlation-derived: business events carrying
 * {@code eventSessionId}/{@code eventId} ensure the row exists. Display fields stay null until a
 * trusted session-lifecycle contract provides them; {@code capacity_snapshot} stays null unless
 * an authoritative snapshot exists in a final event contract. Capacity is never synthesized from
 * sold/issued counts. Older deliveries never overwrite newer snapshots
 * ({@code last_source_event_at} guard).
 */
@Entity
@Table(name = "analytics_session_facts")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class AnalyticsSessionFact {

    @Id
    @Column(name = "event_session_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventSessionId;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "venue_id")
    private UUID venueId;

    @Column(name = "event_title", length = 255)
    private String eventTitle;

    @Column(name = "session_label", length = 255)
    private String sessionLabel;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "capacity_snapshot")
    private Integer capacitySnapshot;

    @Column(name = "last_source_event_at", nullable = false)
    private Instant lastSourceEventAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        AnalyticsSessionFact that = (AnalyticsSessionFact) o;
        return getEventSessionId() != null && Objects.equals(getEventSessionId(), that.getEventSessionId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
