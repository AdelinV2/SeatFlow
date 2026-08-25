package com.seatflow.event.model.entity;

import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "events",
    indexes = {
        @Index(name = "idx_events_status_date", columnList = "status, event_date"),
        @Index(name = "idx_events_category_date", columnList = "category, event_date"),
        @Index(name = "idx_events_venue_id", columnList = "venue_id"),
        @Index(name = "idx_events_created_at", columnList = "created_at DESC")
    }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "venue_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID venueId;

    @Column(nullable = false, length = 255)
    @ToString.Include
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    @ToString.Include
    private String description;

    @Column(nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private EventCategory category;

    @Column(name = "banner_url", length = 1000)
    private String bannerUrl;

    @Column(name = "event_date", nullable = false)
    @ToString.Include
    private Instant eventDate;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private EventStatus status;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<EventPricingTier> pricingTiers = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        Event event = (Event) o;
        return getId() != null && Objects.equals(getId(), event.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
