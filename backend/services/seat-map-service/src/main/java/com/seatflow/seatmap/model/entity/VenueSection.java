package com.seatflow.seatmap.model.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "venue_sections",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_venue_sections_venue_name", columnNames = {"venue_id", "name"})
    },
    indexes = {
        @Index(name = "idx_venue_sections_venue_id", columnList = "venue_id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class VenueSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false, updatable = false)
    private Venue venue;

    @Column(nullable = false, length = 100)
    @ToString.Include
    private String name;

    @Column(name = "row_count", nullable = false)
    @ToString.Include
    private Integer rowCount;

    @Column(name = "col_count", nullable = false)
    @ToString.Include
    private Integer colCount;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "position_x", nullable = false, precision = 12, scale = 3)
    private BigDecimal positionX;

    @Column(name = "position_y", nullable = false, precision = 12, scale = 3)
    private BigDecimal positionY;

    @Column(name = "width", nullable = false, precision = 12, scale = 3)
    private BigDecimal width;

    @Column(name = "height", nullable = false, precision = 12, scale = 3)
    private BigDecimal height;

    @Column(name = "rotation_deg", nullable = false, precision = 7, scale = 3)
    @Builder.Default
    private BigDecimal rotationDeg = BigDecimal.ZERO;

    @Column(name = "z_index", nullable = false)
    @Builder.Default
    private Integer zIndex = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shape_metadata", columnDefinition = "jsonb")
    private JsonNode shapeMetadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Seat> seats = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (positionX == null) {
            positionX = BigDecimal.ZERO.setScale(3);
        }
        if (positionY == null) {
            positionY = BigDecimal.ZERO.setScale(3);
        }
        if (width == null) {
            int cols = colCount != null ? Math.max(colCount, 1) : 1;
            width = BigDecimal.valueOf(cols * 44L).setScale(3);
        }
        if (height == null) {
            int rows = rowCount != null ? Math.max(rowCount, 1) : 1;
            height = BigDecimal.valueOf(rows * 44L).setScale(3);
        }
        if (isActive == null) {
            isActive = true;
        }
        if (rotationDeg == null) {
            rotationDeg = BigDecimal.ZERO.setScale(3);
        } else {
            rotationDeg = rotationDeg.setScale(3, java.math.RoundingMode.HALF_UP);
        }
        if (zIndex == null) {
            zIndex = 0;
        }
        if (positionX != null) {
            positionX = positionX.setScale(3, java.math.RoundingMode.HALF_UP);
        }
        if (positionY != null) {
            positionY = positionY.setScale(3, java.math.RoundingMode.HALF_UP);
        }
        if (width != null) {
            width = width.setScale(3, java.math.RoundingMode.HALF_UP);
        }
        if (height != null) {
            height = height.setScale(3, java.math.RoundingMode.HALF_UP);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        VenueSection that = (VenueSection) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
