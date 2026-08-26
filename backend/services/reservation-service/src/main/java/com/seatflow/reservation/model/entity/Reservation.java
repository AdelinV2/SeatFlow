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
                @Index(name = "idx_res_event_status", columnList = "event_id, status"),
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

    @Column(name = "event_id", nullable = false)
    @ToString.Include
    private UUID eventId;

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
