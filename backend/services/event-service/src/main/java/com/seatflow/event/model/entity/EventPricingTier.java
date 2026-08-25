package com.seatflow.event.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "event_pricing_tiers",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_event_section_tier", columnNames = {"event_id", "section_id", "category_name"})
    },
    indexes = {
        @Index(name = "idx_pricing_event_id", columnList = "event_id"),
        @Index(name = "idx_pricing_event_section", columnList = "event_id, section_id")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class EventPricingTier {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    @ToString.Exclude
    private Event event;

    @Column(name = "section_id", nullable = false)
    @ToString.Include
    private UUID sectionId;

    @Column(name = "category_name", nullable = false, length = 100)
    @ToString.Include
    private String categoryName;

    @Column(nullable = false, precision = 10, scale = 2)
    @ToString.Include
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    @ToString.Include
    private String currency;

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
        EventPricingTier tier = (EventPricingTier) o;
        return getId() != null && Objects.equals(getId(), tier.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
