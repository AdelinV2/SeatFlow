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
 * Latest analytics-safe ticket state for one ticket identity.
 *
 * <p>TASK-P14-003 fact. Sparse rows are allowed: a scan (or revocation) arriving before the
 * issue event creates a row keyed by {@code ticket_id} with correlation filled later, so early
 * evidence is retained and reconciled instead of dropped. {@code first_scanned_at} is
 * set-if-null only, so repeated scans contribute exactly one attendance unit.
 */
@Entity
@Table(name = "analytics_ticket_facts")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@DynamicUpdate
public class AnalyticsTicketFact {

    @Id
    @Column(name = "ticket_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID ticketId;

    @Column(name = "reservation_id")
    @ToString.Include
    private UUID reservationId;

    @Column(name = "event_session_id")
    @ToString.Include
    private UUID eventSessionId;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "first_scanned_at")
    private Instant firstScannedAt;

    @Column(name = "status", nullable = false, length = 64)
    private String status;

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
        AnalyticsTicketFact that = (AnalyticsTicketFact) o;
        return getTicketId() != null && Objects.equals(getTicketId(), that.getTicketId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
