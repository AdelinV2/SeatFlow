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
 * Currency-neutral lifetime operational aggregate for one event session.
 *
 * <p>Each count appears once per session, never once per currency. Recomputed from facts for the
 * affected session key on every accepted source event. {@code capacity_snapshot} mirrors the
 * trusted session-fact snapshot (null when no authoritative snapshot exists); occupancy is then
 * unavailable rather than fabricated.
 */
@Entity
@Table(name = "event_session_metrics")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class EventSessionMetric {

    @Id
    @Column(name = "event_session_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventSessionId;

    @Column(name = "event_id", nullable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "capacity_snapshot")
    private Integer capacitySnapshot;

    @Column(name = "reservations_created", nullable = false)
    private long reservationsCreated;

    @Column(name = "reservations_confirmed", nullable = false)
    private long reservationsConfirmed;

    @Column(name = "reservations_expired", nullable = false)
    private long reservationsExpired;

    @Column(name = "payments_succeeded", nullable = false)
    private long paymentsSucceeded;

    @Column(name = "payments_with_failure", nullable = false)
    private long paymentsWithFailure;

    @Column(name = "refunds_completed", nullable = false)
    private long refundsCompleted;

    @Column(name = "tickets_issued", nullable = false)
    private long ticketsIssued;

    @Column(name = "tickets_revoked", nullable = false)
    private long ticketsRevoked;

    @Column(name = "tickets_scanned", nullable = false)
    private long ticketsScanned;

    @Column(name = "last_projected_event_at")
    private Instant lastProjectedEventAt;

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
        EventSessionMetric that = (EventSessionMetric) o;
        return getEventSessionId() != null && Objects.equals(getEventSessionId(), that.getEventSessionId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
