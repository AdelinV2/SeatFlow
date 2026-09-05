package com.seatflow.reservation.model.entity;

import com.seatflow.reservation.model.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "reservations",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_reservations_idempotency_key", columnNames = "idempotency_key")
        },
        indexes = {
                @Index(name = "idx_res_pending_expires_at", columnList = "expires_at"),
                @Index(name = "idx_res_session_status", columnList = "event_session_id, status"),
                @Index(name = "idx_res_user_status", columnList = "user_id, status"),
                @Index(name = "idx_res_customer_email", columnList = "customer_email"),
                @Index(name = "idx_res_created_at", columnList = "created_at")
        }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    @Column(updatable = false)
    private UUID id;

    @Column(name = "user_id", updatable = false)
    @ToString.Include
    private UUID userId;

    @Column(name = "customer_email", nullable = false, length = 255)
    @ToString.Include
    private String customerEmail;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    // P12-007 retained: non-authoritative parent-event audit/display reference,
    // always derived from the trusted session booking context. Never a booking
    // key; inventory is partitioned by eventSessionId only (ADR-011).
    private UUID eventId;

    @Column(name = "event_session_id", updatable = false)
    @ToString.Include
    // P12-007: authoritative inventory partition (ADR-011). Always populated by
    // the service from the trusted session booking context; V8 aborts migration
    // when NULLs remain. Hard NOT NULL is tracked follow-up TASK-P12-009 (kept
    // nullable until the backfill suites that persist legacy-NULL rows move to
    // a staged pre-constraint schema); SessionIntegrityStartupCheck alerts on
    // NULL session rows at boot.
    private UUID eventSessionId;

    @Column(name = "session_starts_at", updatable = false)
    @ToString.Include
    private Instant sessionStartsAt; // Immutable showing snapshot captured at hold time (P12-004).

    @Column(name = "session_ends_at", updatable = false)
    @ToString.Include
    private Instant sessionEndsAt; // Immutable showing snapshot captured at hold time (P12-004).

    @Column(name = "session_timezone", length = 64, updatable = false)
    private String sessionTimezone; // Nullable IANA ZoneId metadata (P12-004); null until trusted source exposes it.

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @ToString.Include
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    @ToString.Include
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false, length = 255, updatable = false)
    @ToString.Include
    private String idempotencyKey;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    @ToString.Include
    private java.math.BigDecimal totalAmount;

    @Column(name = "seat_count", nullable = false)
    @ToString.Include
    @Builder.Default
    private Integer seatCount = 1;

    @Version
    @Column(nullable = false)
    @ToString.Include
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(
            mappedBy = "reservation",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @Builder.Default
    @ToString.Exclude
    private Set<SeatHold> seatHolds = new HashSet<>();

    public void addSeatHold(SeatHold seatHold) {
        seatHolds.add(seatHold);
        seatHold.setReservation(this);
    }

    public void removeSeatHold(SeatHold seatHold) {
        seatHolds.remove(seatHold);
        seatHold.setReservation(null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Reservation that = (Reservation) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
