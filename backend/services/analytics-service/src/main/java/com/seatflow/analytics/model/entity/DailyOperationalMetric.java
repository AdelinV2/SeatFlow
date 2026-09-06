package com.seatflow.analytics.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
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

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Currency-neutral operational counts for one UTC date + event + session.
 *
 * <p>Exactly one row per key regardless of how many financial currencies are active for the
 * session. Recomputed deterministically from analytics-owned facts; deleted when recomputation
 * proves no contributing facts remain for the date. All money lives in
 * {@code daily_revenue_metrics}, never here.
 */
@Entity
@Table(name = "daily_operational_metrics")
@IdClass(DailyOperationalMetric.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class DailyOperationalMetric {

    @Id
    @Column(name = "metric_date", nullable = false, updatable = false)
    @ToString.Include
    private LocalDate metricDate;

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Id
    @Column(name = "event_session_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventSessionId;

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

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Composite key for {@link DailyOperationalMetric}. */
    public record Key(LocalDate metricDate, UUID eventId, UUID eventSessionId) implements Serializable {
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        DailyOperationalMetric that = (DailyOperationalMetric) o;
        return getMetricDate() != null && getEventId() != null && getEventSessionId() != null
                && Objects.equals(getMetricDate(), that.getMetricDate())
                && Objects.equals(getEventId(), that.getEventId())
                && Objects.equals(getEventSessionId(), that.getEventSessionId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
