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
 * Latest analytics-safe payment state for one payment identity.
 *
 * <p>TASK-P14-003 fact. Completion, failure, and refund evidence are preserved independently so
 * a payment that genuinely failed and later succeeded appears in both historical evidence counts.
 * A refund observed before its completion is stored as a provisional fact
 * ({@code refunded_*} set, {@code completed_*} null) and is excluded from every financial
 * aggregate until the completion event validates it. No card/customer/secret data is stored.
 */
@Entity
@Table(name = "analytics_payment_facts")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class AnalyticsPaymentFact {

    @Id
    @Column(name = "payment_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID paymentId;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID reservationId;

    @Column(name = "event_session_id")
    @ToString.Include
    private UUID eventSessionId;

    @Column(name = "latest_status", length = 64)
    private String latestStatus;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "completed_amount_minor")
    private Long completedAmountMinor;

    @Column(name = "refunded_amount_minor")
    private Long refundedAmountMinor;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

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
        AnalyticsPaymentFact that = (AnalyticsPaymentFact) o;
        return getPaymentId() != null && Objects.equals(getPaymentId(), that.getPaymentId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
