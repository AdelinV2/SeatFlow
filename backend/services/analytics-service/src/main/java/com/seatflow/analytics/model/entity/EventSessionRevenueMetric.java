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
import java.util.Objects;
import java.util.UUID;

/**
 * Currency-keyed lifetime financial aggregate for one event session.
 *
 * <p>One row per {@code (event_session_id, currency)}; a mixed-currency row is never
 * materialized. A refunded purchase remains historically gross; its refund is represented
 * separately so {@code net = gross - refunded} stays exact in minor units.
 */
@Entity
@Table(name = "event_session_revenue_metrics")
@IdClass(EventSessionRevenueMetric.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class EventSessionRevenueMetric {

    @Id
    @Column(name = "event_session_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventSessionId;

    @Id
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    @ToString.Include
    private String currency;

    @Column(name = "event_id", nullable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "payments_succeeded", nullable = false)
    private long paymentsSucceeded;

    @Column(name = "refunds_completed", nullable = false)
    private long refundsCompleted;

    @Column(name = "gross_revenue_minor", nullable = false)
    private long grossRevenueMinor;

    @Column(name = "refunded_revenue_minor", nullable = false)
    private long refundedRevenueMinor;

    @Column(name = "last_projected_event_at")
    private Instant lastProjectedEventAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Composite key for {@link EventSessionRevenueMetric}. */
    public record Key(UUID eventSessionId, String currency) implements Serializable {
    }

    /** Exact integer net revenue: gross minus completed refunds. Never floating arithmetic. */
    public long netRevenueMinor() {
        return grossRevenueMinor - refundedRevenueMinor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        EventSessionRevenueMetric that = (EventSessionRevenueMetric) o;
        return getEventSessionId() != null && getCurrency() != null
                && Objects.equals(getEventSessionId(), that.getEventSessionId())
                && Objects.equals(getCurrency(), that.getCurrency());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
