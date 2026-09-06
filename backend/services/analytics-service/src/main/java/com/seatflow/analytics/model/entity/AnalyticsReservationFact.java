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
 * Latest analytics-safe reservation state derived from reservation lifecycle events.
 *
 * <p>TASK-P14-003 fact: one row per reservation, no PII. Independent lifecycle timestamps
 * ({@code created/confirmed/expired/refunded/cancelled}) preserve raw evidence so out-of-order
 * delivery cannot erase a newer semantic with a stale event. Display/outcome classification is
 * derived by {@code ReservationOutcome} precedence, never by overwriting evidence.
 */
@Entity
@Table(name = "analytics_reservation_facts")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class AnalyticsReservationFact {

    @Id
    @Column(name = "reservation_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID reservationId;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "event_session_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventSessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "seat_count", nullable = false, updatable = false)
    private Integer seatCount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "quoted_total_minor")
    private Long quotedTotalMinor;

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
        AnalyticsReservationFact that = (AnalyticsReservationFact) o;
        return getReservationId() != null && Objects.equals(getReservationId(), that.getReservationId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
