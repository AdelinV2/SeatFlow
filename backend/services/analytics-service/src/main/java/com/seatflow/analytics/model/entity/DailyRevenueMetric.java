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
 * Currency-keyed financial aggregate for one UTC date + event + session + currency.
 *
 * <p>Money is integral minor units; {@code net = gross - refunded} is derived, never stored.
 * One payment contributes gross on its completion date and its refund on the (possibly later)
 * refund date. Currencies are never combined: one session/date with RON + EUR activity owns two
 * rows here and exactly one row in {@code daily_operational_metrics}.
 */
@Entity
@Table(name = "daily_revenue_metrics")
@IdClass(DailyRevenueMetric.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class DailyRevenueMetric {

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

    @Id
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    @ToString.Include
    private String currency;

    @Column(name = "payments_succeeded", nullable = false)
    private long paymentsSucceeded;

    @Column(name = "refunds_completed", nullable = false)
    private long refundsCompleted;

    @Column(name = "gross_revenue_minor", nullable = false)
    private long grossRevenueMinor;

    @Column(name = "refunded_revenue_minor", nullable = false)
    private long refundedRevenueMinor;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Composite key for {@link DailyRevenueMetric}. */
    public record Key(LocalDate metricDate, UUID eventId, UUID eventSessionId, String currency)
            implements Serializable {
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
        DailyRevenueMetric that = (DailyRevenueMetric) o;
        return getMetricDate() != null && getEventId() != null
                && getEventSessionId() != null && getCurrency() != null
                && Objects.equals(getMetricDate(), that.getMetricDate())
                && Objects.equals(getEventId(), that.getEventId())
                && Objects.equals(getEventSessionId(), that.getEventSessionId())
                && Objects.equals(getCurrency(), that.getCurrency());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
