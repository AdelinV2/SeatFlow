package com.seatflow.reservation.model.entity;

import com.seatflow.reservation.model.enums.SeatHoldStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "seat_holds",
        indexes = {
                @Index(name = "idx_holds_reservation_id", columnList = "reservation_id"),
                @Index(name = "idx_holds_event_seat", columnList = "event_id, seat_id"),
                @Index(name = "idx_holds_event_status", columnList = "event_id, status")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    @Column(updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_seat_holds_reservations"))
    @ToString.Exclude
    private Reservation reservation;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "seat_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID seatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @ToString.Include
    private SeatHoldStatus status;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    @ToString.Include
    private BigDecimal price;

    @Column(name = "row_label", length = 20)
    private String rowLabel;

    @Column(name = "seat_number")
    private Integer seatNumber;

    @Column(name = "pricing_tier_id")
    private UUID pricingTierId;

    @Column(name = "ticket_type", length = 100)
    private String ticketType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        SeatHold that = (SeatHold) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
