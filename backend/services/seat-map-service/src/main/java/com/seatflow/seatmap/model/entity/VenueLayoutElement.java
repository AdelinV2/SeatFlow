package com.seatflow.seatmap.model.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.seatmap.model.enums.LayoutElementType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "venue_layout_elements",
    indexes = {
        @Index(name = "idx_venue_layout_elements_venue_id", columnList = "venue_id"),
        @Index(name = "idx_venue_layout_elements_venue_z", columnList = "venue_id, z_index, id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class VenueLayoutElement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false, updatable = false)
    private Venue venue;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private LayoutElementType type;

    @Column(length = 255)
    @ToString.Include
    private String label;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode geometry;

    @Column(name = "z_index", nullable = false)
    @Builder.Default
    @ToString.Include
    private Integer zIndex = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        VenueLayoutElement that = (VenueLayoutElement) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
