package com.seatflow.seatmap.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_seats_section_row_seat", columnNames = {"section_id", "row_label", "seat_number"}),
        @UniqueConstraint(name = "uq_seats_section_grid", columnNames = {"section_id", "grid_x", "grid_y"})
    },
    indexes = {
        @Index(name = "idx_seats_section_id", columnList = "section_id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false, updatable = false)
    private VenueSection section;

    @Column(name = "row_label", nullable = false, length = 10)
    @ToString.Include
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    @ToString.Include
    private Integer seatNumber;

    @Column(name = "grid_x", nullable = false)
    private Integer gridX;

    @Column(name = "grid_y", nullable = false)
    private Integer gridY;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Seat seat = (Seat) o;
        return getId() != null && Objects.equals(getId(), seat.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
