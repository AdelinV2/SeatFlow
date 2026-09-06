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
 * Retained reservation-scoped ticket-revocation evidence (TASK-P14-003, V2).
 *
 * <p>Used only when a revocation event identifies a reservation without enumerating ticket IDs.
 * The event is persisted here instead of dropped; when ticket issue events for that reservation
 * arrive or replay later, reconciliation marks the matching tickets revoked. Per-ticket
 * revocations update {@code analytics_ticket_facts} directly and never touch this table.
 */
@Entity
@Table(name = "analytics_ticket_revocation_facts")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class AnalyticsTicketRevocationFact {

    @Id
    @Column(name = "reservation_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID reservationId;

    @Column(name = "event_session_id")
    private UUID eventSessionId;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    @Column(name = "source_event_id", length = 128)
    private String sourceEventId;

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
        AnalyticsTicketRevocationFact that = (AnalyticsTicketRevocationFact) o;
        return getReservationId() != null && Objects.equals(getReservationId(), that.getReservationId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
