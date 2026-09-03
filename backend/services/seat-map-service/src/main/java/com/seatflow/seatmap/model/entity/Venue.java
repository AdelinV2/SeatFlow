package com.seatflow.seatmap.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "venues",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_venues_name_city", columnNames = {"name", "city"})
    },
    indexes = {
        @Index(name = "idx_venues_city", columnList = "city"),
        @Index(name = "idx_venues_name", columnList = "name")
    }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(nullable = false, length = 255)
    @ToString.Include
    private String name;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(nullable = false, length = 100)
    @ToString.Include
    private String city;

    @Column(nullable = false, length = 100)
    @Builder.Default
    private String country = "USA";

    @Column(nullable = false)
    @ToString.Include
    private Integer capacity;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(name = "layout_version", nullable = false)
    @Builder.Default
    private Long layoutVersion = 0L;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<VenueSection> sections = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Venue venue = (Venue) o;
        return getId() != null && Objects.equals(getId(), venue.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
