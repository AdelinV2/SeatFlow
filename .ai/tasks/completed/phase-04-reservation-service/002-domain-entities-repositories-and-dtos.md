# TASK-P04-002: Reservation Domain Entities, Repositories, DTOs & MapStruct Mappers

## 1. Task Metadata
- **Task ID:** `TASK-P04-002`
- **Git Branch:** `feat/p04-002-domain-entities-repositories-and-dtos`
- **Target Module:** `backend/services/reservation-service`
- **Phase:** `Phase 04 - Reservation & Hold Service`
- **Related Specs:** `.ai/architecture/03-database-models.md` (Section 2.4), `.ai/architecture/06-api-contracts.md` (Section 2.4)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the domain persistence entities, Spring Data JPA repositories, immutable Java Record DTOs, and MapStruct mappers for the `reservation-service`. This task establishes the core data models, custom locking repository queries, and mapping boundaries; services, REST controllers, and Kafka consumers will be built on these contracts in subsequent tasks.

### Critical Invariants to Enforce:
- [ ] Entities use explicit Lombok annotations (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@AllArgsConstructor`); **NEVER use `@Data` or Lombok `@EqualsAndHashCode` on JPA entities**.
- [ ] `Reservation` entity has `@Version Long version`, `@DynamicUpdate`, explicit `@Table` unique constraints and index definitions matching Flyway V1 DDL, and Hibernate-safe `equals()` / `hashCode()` based on `Hibernate.getClass(this)` and `getId()`.
- [ ] `Reservation` maintains a bidirectional `@OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)` relationship with `SeatHold`.
- [ ] **ADR-001 (Guest Checkout):** `Reservation.userId` is a nullable `UUID`, while `customerEmail` is mandatory (`@Column(name = "customer_email", nullable = false)`).
- [ ] **Invariant #1 (Max 10 Seats):** `CreateReservationRequest` validates seat collection with `@NotEmpty @Size(min = 1, max = 10, message = "Maximum 10 seats per reservation")`.
- [ ] **Invariant #3 (Zero Double-Booking Guarantee):** `SeatHold` declares index metadata matching `uq_active_seat_hold` on `(event_id, seat_id)`.
- [ ] All request and response DTOs are Java 21 Records decorated with OpenAPI `@Schema` annotations and Jakarta Bean Validation constraints.
- [ ] MapStruct mappers configure `componentModel = MappingConstants.ComponentModel.SPRING` and `unmappedTargetPolicy = ReportingPolicy.ERROR`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/model/enums/ReservationStatus.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/model/enums/SeatHoldStatus.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/model/entity/Reservation.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/model/entity/SeatHold.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/model/entity/OutboxEvent.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/repository/ReservationRepository.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/repository/SeatHoldRepository.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/repository/OutboxEventRepository.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/repository/projection/ActiveSeatHoldProjection.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/web/dto/request/CreateReservationRequest.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/web/dto/response/ReservationResponse.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/web/dto/response/SeatHoldResponse.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/web/dto/response/SeatAvailabilityResponse.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/web/dto/response/EventSeatStatusResponse.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/mapper/ReservationMapper.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/mapper/SeatHoldMapper.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/repository/ReservationRepositoryTest.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/repository/SeatHoldRepositoryTest.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/mapper/ReservationMapperTest.java`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/mapper/SeatHoldMapperTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Enums
Create `ReservationStatus` and `SeatHoldStatus` in `com.seatflow.reservation.model.enums`:

```java
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}

public enum SeatHoldStatus {
    HELD,
    SOLD,
    RELEASED
}
```

### 4.2 JPA Entities

#### `Reservation.java`
```java
package com.seatflow.reservation.model.entity;

import com.seatflow.reservation.model.enums.ReservationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "reservations",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_reservations_idempotency_key", columnNames = {"idempotency_key"})
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
    private UUID id;

    @Column(name = "user_id", updatable = false)
    @ToString.Include
    private UUID userId; // Nullable for guest checkouts (ADR-001)

    @Column(name = "customer_email", nullable = false, updatable = false)
    @ToString.Include
    private String customerEmail;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    @ToString.Include
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "seat_count", nullable = false)
    @Builder.Default
    private Integer seatCount = 1;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SeatHold> seatHolds = new ArrayList<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void addSeatHold(SeatHold hold) {
        seatHolds.add(hold);
        hold.setReservation(this);
    }

    public void removeSeatHold(SeatHold hold) {
        seatHolds.remove(hold);
        hold.setReservation(null);
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
```

#### `SeatHold.java`
```java
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
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false, updatable = false)
    private Reservation reservation;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "seat_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID seatId;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private SeatHoldStatus status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        SeatHold seatHold = (SeatHold) o;
        return getId() != null && Objects.equals(getId(), seatHold.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

#### `OutboxEvent.java`
```java
package com.seatflow.reservation.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_res_outbox_unpub", columnList = "created_at"),
        @Index(name = "idx_res_outbox_aggregate", columnList = "aggregate_id, created_at")
    }
)
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        OutboxEvent that = (OutboxEvent) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

---

### 4.3 Repository Contracts

#### `ReservationRepository.java`
```java
package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.Reservation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {"seatHolds"})
    Optional<Reservation> findWithSeatHoldsById(UUID id);

    @EntityGraph(attributePaths = {"seatHolds"})
    Optional<Reservation> findWithSeatHoldsByIdempotencyKey(String idempotencyKey);

    List<Reservation> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Reservation> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    /**
     * Polls and locks expired pending reservations using PostgreSQL FOR UPDATE SKIP LOCKED.
     * LIMIT is explicitly declared before FOR UPDATE to maintain valid PostgreSQL syntax.
     */
    @Query(value = """
            SELECT * FROM reservations
            WHERE status = 'PENDING' AND expires_at < :now
            ORDER BY expires_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Reservation> findExpiredReservationsForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Reservation r SET r.userId = :userId, r.updatedAt = :now WHERE r.customerEmail = :email AND r.userId IS NULL")
    int updateUserIdForGuestEmail(@Param("userId") UUID userId, @Param("email") String email, @Param("now") Instant now);
}
```

#### `SeatHoldRepository.java`
```java
package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    List<SeatHold> findByEventIdAndStatusIn(UUID eventId, Collection<SeatHoldStatus> statuses);

    List<SeatHold> findByEventIdAndSeatIdInAndStatusIn(UUID eventId, Collection<UUID> seatIds, Collection<SeatHoldStatus> statuses);

    List<SeatHold> findByReservationId(UUID reservationId);

    @Query("SELECT s.seatId AS seatId, s.status AS status FROM SeatHold s WHERE s.eventId = :eventId AND s.status IN ('HELD', 'SOLD')")
    List<ActiveSeatHoldProjection> findActiveSeatHoldsByEventId(@Param("eventId") UUID eventId);
}
```

#### `ActiveSeatHoldProjection.java`
```java
package com.seatflow.reservation.repository.projection;

import com.seatflow.reservation.model.enums.SeatHoldStatus;

import java.util.UUID;

public interface ActiveSeatHoldProjection {
    UUID getSeatId();
    SeatHoldStatus getStatus();
}
```

#### `OutboxEventRepository.java`
```java
package com.seatflow.reservation.repository;

import com.seatflow.reservation.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL AND retry_count < :maxRetry
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdate(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.publishedAt = :publishedAt WHERE o.id = :id AND o.publishedAt IS NULL")
    int markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.retryCount = o.retryCount + 1 WHERE o.id = :id AND o.retryCount < :maxRetry")
    int incrementRetryCount(@Param("id") UUID id, @Param("maxRetry") int maxRetry);
}
```

---

### 4.4 DTO Records Contract

```java
package com.seatflow.reservation.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.Set;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Schema(description = "Request body for creating a 15-minute temporary seat reservation hold")
public record CreateReservationRequest(

    @Schema(description = "UUID of the target event", example = "123e4567-e89b-12d3-a456-426614174000", requiredMode = REQUIRED)
    @NotNull(message = "Event ID is required")
    UUID eventId,

    @Schema(description = "Set of seat UUIDs to hold. Maximum 10 seats per reservation.", requiredMode = REQUIRED)
    @NotEmpty(message = "At least one seat must be selected")
    @Size(min = 1, max = 10, message = "Maximum 10 seats per reservation")
    Set<@NotNull UUID> seatIds,

    @Schema(description = "Unique client-generated idempotency key", example = "hold-idem-98765-abcd", requiredMode = REQUIRED)
    @NotBlank(message = "Idempotency key is required")
    @Size(max = 255)
    String idempotencyKey,

    @Schema(description = "Customer email address. Required for unauthenticated guest checkouts.", example = "guest@example.com", requiredMode = NOT_REQUIRED)
    @Email(message = "Invalid customer email format")
    @Size(max = 255)
    String customerEmail
) {}
```

```java
package com.seatflow.reservation.web.dto.response;

import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Detailed reservation hold response")
public record ReservationResponse(
    @Schema(description = "Unique reservation identifier") UUID id,
    @Schema(description = "Target event identifier") UUID eventId,
    @Schema(description = "Authenticated customer ID (null for guests)") UUID userId,
    @Schema(description = "Customer email address") String customerEmail,
    @Schema(description = "Current reservation status") ReservationStatus status,
    @Schema(description = "Hold expiration timestamp (ISO-8601 UTC)") Instant expiresAt,
    @Schema(description = "Authoritative total reservation price") BigDecimal totalAmount,
    @Schema(description = "Count of reserved seats") Integer seatCount,
    @Schema(description = "List of individual seat holds") List<SeatHoldResponse> seats,
    @Schema(description = "Creation timestamp") Instant createdAt
) {}

@Schema(description = "Individual seat hold summary")
public record SeatHoldResponse(
    @Schema(description = "Seat hold record identifier") UUID id,
    @Schema(description = "Target physical seat identifier") UUID seatId,
    @Schema(description = "Hold status (HELD, SOLD, RELEASED)") SeatHoldStatus status,
    @Schema(description = "Price assigned to this seat hold") BigDecimal price
) {}

@Schema(description = "Live seat availability response for an event")
public record SeatAvailabilityResponse(
    @Schema(description = "Target event identifier") UUID eventId,
    @Schema(description = "List of current seat statuses") List<EventSeatStatusResponse> seats
) {}

@Schema(description = "Seat status tuple")
public record EventSeatStatusResponse(
    @Schema(description = "Seat unique identifier") UUID seatId,
    @Schema(description = "Live status (HELD, SOLD)") SeatHoldStatus status
) {}
```

---

### 4.5 MapStruct Mappers

```java
package com.seatflow.reservation.mapper;

import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    uses = {SeatHoldMapper.class},
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface ReservationMapper {

    @Mapping(target = "seats", source = "seatHolds")
    ReservationResponse toResponse(Reservation reservation);
}
```

```java
package com.seatflow.reservation.mapper;

import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.web.dto.response.SeatHoldResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface SeatHoldMapper {

    SeatHoldResponse toResponse(SeatHold seatHold);
}
```

---

### 4.6 Repository Slice & Mapper Tests Contract
- `ReservationRepositoryTest`: `@DataJpaTest`, `@ActiveProfiles("test")`, `@Testcontainers` with PostgreSQL 16.
  - Asserts `findByIdempotencyKey` lookup and unique constraint violation on duplicate key.
  - Asserts `findWithSeatHoldsById` loads lazy child holds in a single query graph.
  - Asserts `findExpiredReservationsForUpdate` returns only `PENDING` reservations where `expires_at < now` using `SKIP LOCKED` and batch limit.
  - Asserts `updateUserIdForGuestEmail` updates historical guest reservations with `userId = null` to the given `userId`.
- `SeatHoldRepositoryTest`: `@DataJpaTest` asserting that attempting to persist two `SeatHold` records for the same `(eventId, seatId)` with status `HELD` throws a `DataIntegrityViolationException` (violating `uq_active_seat_hold`).
- `ReservationMapperTest` & `SeatHoldMapperTest`: Unit tests verifying accurate mapping of all scalar fields, child seat hold lists, and enum representations.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p04-002-domain-entities-repositories-and-dtos` from `develop`.
2. Implement enums `ReservationStatus` and `SeatHoldStatus`.
3. Create JPA entities `Reservation`, `SeatHold`, and `OutboxEvent` adhering strictly to explicit Lombok and Hibernate-safe equality.
4. Implement repositories `ReservationRepository`, `SeatHoldRepository`, and `OutboxEventRepository` with custom locking queries and projections.
5. Create all immutable DTO records with Jakarta Validation and OpenAPI `@Schema`.
6. Implement MapStruct mappers `ReservationMapper` and `SeatHoldMapper`.
7. Write PostgreSQL Testcontainers repository slice tests and mapper unit tests.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/reservation-service -Dtest=ReservationRepositoryTest,SeatHoldRepositoryTest,ReservationMapperTest,SeatHoldMapperTest
```

- [ ] All entity definitions, constraints, and column mappings match Flyway V1 and V2 DDL.
- [ ] Concurrency unique index `uq_active_seat_hold` and partial index `idx_res_pending_expires_at` are verified via `@DataJpaTest`.
- [ ] MapStruct mappers compile with `ReportingPolicy.ERROR` and zero unmapped fields.
- [ ] Task file is moved to `.ai/tasks/completed/phase-04-reservation-service/002-domain-entities-repositories-and-dtos.md` when complete.
