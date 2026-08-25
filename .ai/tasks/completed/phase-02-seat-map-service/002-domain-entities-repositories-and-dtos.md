# TASK-P02-002: Domain Entities, Repositories, DTOs & MapStruct Mappers

## 1. Task Metadata
- **Task ID:** `TASK-P02-002`
- **Git Branch:** `feat/p02-002-domain-entities-repositories-and-dtos`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 02 - Seat Map & Venue Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/03-database-models.md` (Section 2.2), `.ai/architecture/05-messaging-and-outbox.md` (Section 2.2), `.ai/architecture/06-api-contracts.md` (Section 2.2)
- **Related ADRs:** `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md` (§3.3 JPA Standards)
- **Dependencies:** `TASK-P02-001` (Module setup, POM, Flyway migrations must exist)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement JPA domain entities (`Venue`, `VenueSection`, `Seat`, `OutboxEvent`), Spring Data JPA repositories, request/response DTO Java Records, domain event records (`VenueCreatedEvent`, `VenueSectionCreatedEvent`), and MapStruct mappers (`VenueMapper`, `VenueSectionMapper`, `SeatMapper`).

### Critical Invariants to Enforce:
- [ ] JPA entities use explicit Lombok annotations (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@AllArgsConstructor`) — NEVER `@Data`.
- [ ] Hibernate-safe `equals()` and `hashCode()` implemented explicitly on all entities (using `Hibernate.getClass()` and `getId()`).
- [ ] `@ToString(onlyExplicitlyIncluded = true)` on all entities — never include lazy-loaded associations.
- [ ] `Venue` entity uses `@Version private Long version;` for optimistic concurrency control.
- [ ] `Venue` entity uses `@DynamicUpdate` for minimal SQL update statements.
- [ ] `@Table` annotations explicitly declare `name`, `uniqueConstraints`, and `indexes` matching Flyway DDL.
- [ ] `OutboxEvent.payload` mapped with `@JdbcTypeCode(SqlTypes.JSON)` for PostgreSQL JSONB.
- [ ] All DTOs and event payloads are immutable Java Records with `@Schema` annotations.
- [ ] Domain event records implement `DomainEvent` from `common-events`.
- [ ] MapStruct mappers use `componentModel = MappingConstants.ComponentModel.SPRING` and `unmappedTargetPolicy = ReportingPolicy.ERROR`.
- [ ] No direct entity exposure in REST responses — all mapping through MapStruct.
- [ ] Request DTOs include Jakarta Bean Validation annotations.

---

## 3. Exact File Inventory

All paths relative to `backend/services/seat-map-service/`.

- `[NEW]` `src/main/java/com/seatflow/seatmap/model/entity/Venue.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/model/entity/VenueSection.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/model/entity/Seat.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/model/entity/OutboxEvent.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/repository/VenueRepository.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/repository/VenueSectionRepository.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/repository/SeatRepository.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/repository/OutboxEventRepository.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/request/CreateVenueRequest.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/request/UpdateVenueRequest.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/request/CreateVenueSectionRequest.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/request/UpdateSeatStatusRequest.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/response/VenueResponse.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/response/VenueDetailResponse.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/response/VenueSectionResponse.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/response/SeatResponse.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/response/VenueSeatMapLayoutResponse.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/dto/response/SectionLayoutResponse.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/messaging/event/VenueCreatedEvent.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/messaging/event/VenueSectionCreatedEvent.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/mapper/VenueMapper.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/mapper/VenueSectionMapper.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/mapper/SeatMapper.java`

---

## 4. Technical Specifications & Contracts

### 4.1 JPA Entity: `Venue`
```java
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
```

### 4.2 JPA Entity: `VenueSection`
```java
package com.seatflow.seatmap.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "section", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Seat> seats = new ArrayList<>();

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
```

### 4.3 JPA Entity: `Seat`
```java
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
```

### 4.4 JPA Entity: `OutboxEvent`
```java
package com.seatflow.seatmap.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    @ToString.Include
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;
}
```

### 4.5 Repository: `VenueRepository`
```java
package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Venue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface VenueRepository extends JpaRepository<Venue, UUID> {

    Page<Venue> findAll(Pageable pageable);

    @Query("SELECT v FROM Venue v WHERE (:city IS NULL OR v.city = :city) AND (:name IS NULL OR LOWER(v.name) LIKE LOWER(CONCAT('%', :name, '%')))")
    Page<Venue> findByFilters(@Param("city") String city, @Param("name") String name, Pageable pageable);

    boolean existsByNameAndCity(String name, String city);
}
```

### 4.6 Repository: `VenueSectionRepository`
```java
package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.VenueSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VenueSectionRepository extends JpaRepository<VenueSection, UUID> {

    List<VenueSection> findByVenueIdOrderByNameAsc(UUID venueId);

    boolean existsByVenueIdAndName(UUID venueId, String name);
}
```

### 4.7 Repository: `SeatRepository`
```java
package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findBySectionIdOrderByGridYAscGridXAsc(UUID sectionId);

    @Query("SELECT s FROM Seat s WHERE s.section.id = :sectionId AND s.isActive = true ORDER BY s.gridY ASC, s.gridX ASC")
    List<Seat> findActiveSeatsBySectionId(@Param("sectionId") UUID sectionId);

    @Query("SELECT s FROM Seat s WHERE s.section.venue.id = :venueId AND s.isActive = true ORDER BY s.section.name ASC, s.gridY ASC, s.gridX ASC")
    List<Seat> findActiveSeatsForVenueLayout(@Param("venueId") UUID venueId);

    Optional<Seat> findByIdAndSectionId(UUID seatId, UUID sectionId);

    long countBySectionIdAndIsActiveTrue(UUID sectionId);

    @Query("SELECT COUNT(s) FROM Seat s WHERE s.section.venue.id = :venueId AND s.isActive = true")
    long countActiveSeatsByVenueId(@Param("venueId") UUID venueId);
}
```

### 4.8 Repository: `OutboxEventRepository`
```java
package com.seatflow.seatmap.repository;

import com.seatflow.seatmap.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

    /**
     * Claims a batch of unpublished, not-yet-exhausted events using a row-level lock that
     * skips rows already locked by another publisher instance (PostgreSQL {@code FOR UPDATE SKIP LOCKED}).
     * Events at the retry ceiling are excluded so they are parked instead of being polled forever.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL AND retry_count < :maxRetry
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdate(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.publishedAt = :now WHERE o.id = :id AND o.publishedAt IS NULL")
    int markPublished(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE OutboxEvent o SET o.retryCount = o.retryCount + 1 WHERE o.id = :id AND o.retryCount < :max")
    int incrementRetryCount(@Param("id") UUID id, @Param("max") int max);
}
```

### 4.9 Request DTOs

#### `CreateVenueRequest`
```java
package com.seatflow.seatmap.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating a new venue")
public record CreateVenueRequest(

    @Schema(description = "Venue name", example = "Grand Theatre")
    @NotBlank(message = "Venue name is required")
    @Size(max = 255, message = "Venue name must not exceed 255 characters")
    String name,

    @Schema(description = "Full street address", example = "123 Main Street")
    @NotBlank(message = "Address is required")
    @Size(max = 500, message = "Address must not exceed 500 characters")
    String address,

    @Schema(description = "City where the venue is located", example = "New York")
    @NotBlank(message = "City is required")
    @Size(max = 100, message = "City must not exceed 100 characters")
    String city,

    @Schema(description = "Country where the venue is located", example = "USA")
    @Size(max = 100, message = "Country must not exceed 100 characters")
    String country,

    @Schema(description = "Total venue capacity", example = "500")
    @NotNull(message = "Capacity is required")
    @Min(value = 1, message = "Capacity must be at least 1")
    Integer capacity

) {}
```

#### `UpdateVenueRequest`
```java
package com.seatflow.seatmap.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for updating an existing venue")
public record UpdateVenueRequest(

    @Schema(description = "Venue name", example = "Grand Theatre Updated")
    @Size(max = 255, message = "Venue name must not exceed 255 characters")
    String name,

    @Schema(description = "Full street address", example = "456 Broadway")
    @Size(max = 500, message = "Address must not exceed 500 characters")
    String address,

    @Schema(description = "City", example = "New York")
    @Size(max = 100, message = "City must not exceed 100 characters")
    String city,

    @Schema(description = "Country", example = "USA")
    @Size(max = 100, message = "Country must not exceed 100 characters")
    String country,

    @Schema(description = "Total venue capacity", example = "600")
    @Min(value = 1, message = "Capacity must be at least 1")
    Integer capacity

) {}
```

#### `CreateVenueSectionRequest`
```java
package com.seatflow.seatmap.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating a venue section with automatic seat grid generation")
public record CreateVenueSectionRequest(

    @Schema(description = "Section name (e.g., 'Orchestra', 'Balcony', 'VIP Lounge')", example = "Orchestra")
    @NotBlank(message = "Section name is required")
    @Size(max = 100, message = "Section name must not exceed 100 characters")
    String name,

    @Schema(description = "Number of rows in the seat grid", example = "10")
    @NotNull(message = "Row count is required")
    @Min(value = 1, message = "Row count must be at least 1")
    Integer rowCount,

    @Schema(description = "Number of columns (seats per row) in the seat grid", example = "20")
    @NotNull(message = "Column count is required")
    @Min(value = 1, message = "Column count must be at least 1")
    Integer colCount

) {}
```

#### `UpdateSeatStatusRequest`
```java
package com.seatflow.seatmap.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request body for toggling seat active/inactive status")
public record UpdateSeatStatusRequest(

    @Schema(description = "Whether the seat should be active (bookable) or inactive", example = "false")
    @NotNull(message = "Active status is required")
    Boolean isActive

) {}
```

### 4.10 Response DTOs

#### `VenueResponse`
```java
package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Venue summary response for list views")
public record VenueResponse(
    @Schema(description = "Venue UUID") UUID id,
    @Schema(description = "Venue name") String name,
    @Schema(description = "Full street address") String address,
    @Schema(description = "City") String city,
    @Schema(description = "Country") String country,
    @Schema(description = "Total capacity") Integer capacity,
    @Schema(description = "Creation timestamp") Instant createdAt
) {}
```

#### `VenueDetailResponse`
```java
package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Venue detail response including sections")
public record VenueDetailResponse(
    @Schema(description = "Venue UUID") UUID id,
    @Schema(description = "Venue name") String name,
    @Schema(description = "Full street address") String address,
    @Schema(description = "City") String city,
    @Schema(description = "Country") String country,
    @Schema(description = "Total capacity") Integer capacity,
    @Schema(description = "Venue sections") List<VenueSectionResponse> sections,
    @Schema(description = "Creation timestamp") Instant createdAt
) {}
```

#### `VenueSectionResponse`
```java
package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Venue section response")
public record VenueSectionResponse(
    @Schema(description = "Section UUID") UUID id,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows") Integer rowCount,
    @Schema(description = "Number of columns (seats per row)") Integer colCount,
    @Schema(description = "Total active seats in this section") Long activeSeatCount
) {}
```

#### `SeatResponse`
```java
package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Individual seat in a venue section")
public record SeatResponse(
    @Schema(description = "Seat UUID") UUID seatId,
    @Schema(description = "Row label (e.g., 'A', 'B', 'C')") String rowLabel,
    @Schema(description = "Seat number within the row") Integer seatNumber,
    @Schema(description = "Grid X coordinate (0-based column index)") Integer gridX,
    @Schema(description = "Grid Y coordinate (0-based row index)") Integer gridY,
    @Schema(description = "Whether the seat is active/bookable") Boolean isActive
) {}
```

#### `VenueSeatMapLayoutResponse`
```java
package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Complete venue seat map layout with all sections and seats")
public record VenueSeatMapLayoutResponse(
    @Schema(description = "Venue UUID") UUID venueId,
    @Schema(description = "Venue name") String name,
    @Schema(description = "Total capacity") Integer capacity,
    @Schema(description = "Sections with seat grids") List<SectionLayoutResponse> sections
) {}
```

#### `SectionLayoutResponse`
```java
package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Section layout with complete seat grid")
public record SectionLayoutResponse(
    @Schema(description = "Section UUID") UUID sectionId,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows") Integer rowCount,
    @Schema(description = "Number of columns") Integer colCount,
    @Schema(description = "Seats in this section") List<SeatResponse> seats
) {}
```

### 4.11 Domain Events

#### `VenueCreatedEvent`
```java
package com.seatflow.seatmap.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Domain event published when a new venue is created")
public record VenueCreatedEvent(
    @Schema(description = "Venue UUID") UUID venueId,
    @Schema(description = "Venue name") String name,
    @Schema(description = "City") String city,
    @Schema(description = "Total capacity") Integer capacity,
    @Schema(description = "Creation timestamp") Instant createdAt
) implements DomainEvent {}
```

#### `VenueSectionCreatedEvent`
```java
package com.seatflow.seatmap.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Domain event published when a new venue section is created with seats")
public record VenueSectionCreatedEvent(
    @Schema(description = "Section UUID") UUID sectionId,
    @Schema(description = "Parent venue UUID") UUID venueId,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows") Integer rowCount,
    @Schema(description = "Number of columns") Integer colCount,
    @Schema(description = "Total seats generated") Integer totalSeats,
    @Schema(description = "Creation timestamp") Instant createdAt
) implements DomainEvent {}
```

### 4.12 MapStruct Mappers

#### `VenueMapper`
```java
package com.seatflow.seatmap.mapper;

import com.seatflow.seatmap.model.entity.Venue;
import com.seatflow.seatmap.web.dto.response.VenueDetailResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VenueMapper {

    VenueResponse toResponse(Venue venue);

    @Mapping(target = "id", source = "venue.id")
    @Mapping(target = "name", source = "venue.name")
    @Mapping(target = "address", source = "venue.address")
    @Mapping(target = "city", source = "venue.city")
    @Mapping(target = "country", source = "venue.country")
    @Mapping(target = "capacity", source = "venue.capacity")
    @Mapping(target = "sections", source = "sections")
    @Mapping(target = "createdAt", source = "venue.createdAt")
    VenueDetailResponse toDetailResponse(Venue venue, List<VenueSectionResponse> sections);
}
```

#### `VenueSectionMapper`
```java
package com.seatflow.seatmap.mapper;

import com.seatflow.seatmap.model.entity.VenueSection;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VenueSectionMapper {

    @Mapping(target = "id", source = "section.id")
    @Mapping(target = "name", source = "section.name")
    @Mapping(target = "rowCount", source = "section.rowCount")
    @Mapping(target = "colCount", source = "section.colCount")
    @Mapping(target = "activeSeatCount", source = "activeSeatCount")
    VenueSectionResponse toResponse(VenueSection section, Long activeSeatCount);
}
```

#### `SeatMapper`
```java
package com.seatflow.seatmap.mapper;

import com.seatflow.seatmap.model.entity.Seat;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SeatMapper {

    @Mapping(source = "id", target = "seatId")
    SeatResponse toResponse(Seat seat);
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p02-002-domain-entities-repositories-and-dtos develop`
2. **Step 2 — Venue Entity:** Create `Venue.java` with `@DynamicUpdate`, `@Version`, explicit `equals`/`hashCode`, `@OneToMany` to sections.
3. **Step 3 — VenueSection Entity:** Create `VenueSection.java` with `@ManyToOne` to Venue, `@OneToMany` to seats.
4. **Step 4 — Seat Entity:** Create `Seat.java` with `@ManyToOne` to VenueSection, grid coordinate fields.
5. **Step 5 — OutboxEvent Entity:** Create `OutboxEvent.java` with `@JdbcTypeCode(SqlTypes.JSON)` for JSONB payload.
6. **Step 6 — Repositories:** Create all 4 repository interfaces with specified query methods.
7. **Step 7 — Request DTOs:** Create all 4 request records with Jakarta Bean Validation and `@Schema` annotations.
8. **Step 8 — Response DTOs:** Create all 6 response records with `@Schema` annotations.
9. **Step 9 — Domain Events:** Create `VenueCreatedEvent` and `VenueSectionCreatedEvent` implementing `DomainEvent`.
10. **Step 10 — MapStruct Mappers:** Create `VenueMapper`, `VenueSectionMapper`, `SeatMapper`.
11. **Step 11 — Verify Compilation:** Run `mvn clean compile -pl services/seat-map-service -am` from `backend/`.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean compile -pl services/seat-map-service -am
```
- [ ] All entities compile cleanly with explicit Lombok annotations (NO `@Data`).
- [ ] All entities have Hibernate-safe `equals()` and `hashCode()` implementations.
- [ ] `Venue` entity has `@Version` and `@DynamicUpdate`.
- [ ] `OutboxEvent` uses `@JdbcTypeCode(SqlTypes.JSON)` for JSONB payload.
- [ ] MapStruct generates implementation classes for all 3 mappers.
- [ ] All DTO records are immutable with `@Schema` and validation annotations.
- [ ] Domain events implement `DomainEvent` from `common-events`.
- [ ] Task file is moved to `.ai/tasks/completed/phase-02-seat-map-service/002-domain-entities-repositories-and-dtos.md`.
