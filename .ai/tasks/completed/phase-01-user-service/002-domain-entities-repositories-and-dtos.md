# TASK-P01-002: Domain Entities, Repositories, DTOs & MapStruct Mapper

## 1. Task Metadata
- **Task ID:** `TASK-P01-002`
- **Git Branch:** `feat/p01-002-domain-entities-repositories-and-dtos`
- **Target Module:** `backend/services/user-service`
- **Phase:** `Phase 01 - User Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/03-database-models.md` (Section 2.1), `.ai/architecture/05-messaging-and-outbox.md` (Section 2.2: UserRegisteredEvent), `.ai/architecture/06-api-contracts.md` (Section 2.1)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md` (Section 3.6)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the JPA domain entities (`User`, `OutboxEvent`), Spring Data JPA repositories, request/response DTO records, the `UserRegisteredEvent` domain event record, and the MapStruct `UserMapper`.

### Critical Invariants to Enforce:
- [ ] JPA entities use explicit Lombok annotations (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@AllArgsConstructor`) — NEVER `@Data`.
- [ ] `User` entity has lean identity fields (`externalId`, `email`, `phone`, `createdAt`, `updatedAt`) aligned with Entra ID claims and downstream domain events.
- [ ] All DTOs and event payloads are immutable Java Records with `@Schema` annotations.
- [ ] `UserRegisteredEvent` implements `DomainEvent` from `common-events`.
- [ ] MapStruct mapper uses `componentModel = MappingConstants.ComponentModel.SPRING` and `unmappedTargetPolicy = ReportingPolicy.ERROR`.
- [ ] No direct entity exposure in REST responses — all mapping goes through `UserMapper`.
- [ ] Request DTOs include Jakarta Bean Validation annotations.

---

## 3. Exact File Inventory

- `[NEW]` `src/main/java/com/seatflow/user/model/entity/User.java`
- `[NEW]` `src/main/java/com/seatflow/user/model/entity/OutboxEvent.java`
- `[NEW]` `src/main/java/com/seatflow/user/repository/UserRepository.java`
- `[NEW]` `src/main/java/com/seatflow/user/repository/OutboxEventRepository.java`
- `[NEW]` `src/main/java/com/seatflow/user/web/dto/request/UpdateUserProfileRequest.java`
- `[NEW]` `src/main/java/com/seatflow/user/web/dto/response/UserProfileResponse.java`
- `[NEW]` `src/main/java/com/seatflow/user/messaging/event/UserRegisteredEvent.java`
- `[NEW]` `src/main/java/com/seatflow/user/mapper/UserMapper.java`

All paths relative to `backend/services/user-service/`.

---

## 4. Technical Specifications & Contracts

### 4.1 JPA Entity: `User` (`com.seatflow.user.model.entity`)
```java
package com.seatflow.user.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "external_id", nullable = false, unique = true)
    private String externalId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

### 4.2 JPA Entity: `OutboxEvent` (`com.seatflow.user.model.entity`)
```java
package com.seatflow.user.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
```

### 4.3 Repository: `UserRepository`
```java
package com.seatflow.user.repository;

import com.seatflow.user.model.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByExternalId(String externalId);

    Optional<User> findByEmail(String email);

    boolean existsByExternalId(String externalId);

    boolean existsByEmail(String email);

    Page<User> findAll(Pageable pageable);
}
```

### 4.4 Repository: `OutboxEventRepository`
```java
package com.seatflow.user.repository;

import com.seatflow.user.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
}
```

### 4.5 Request DTO: `UpdateUserProfileRequest`
```java
package com.seatflow.user.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for updating user profile details")
public record UpdateUserProfileRequest(

    @Schema(description = "User's phone number", example = "+1-555-0199")
    @Size(max = 50, message = "Phone number must not exceed 50 characters")
    String phone

) {}
```

### 4.6 Response DTO: `UserProfileResponse`
```java
package com.seatflow.user.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "User profile response")
public record UserProfileResponse(

    @Schema(description = "Internal user UUID", example = "123e4567-e89b-12d3-a456-426614174000")
    UUID id,

    @Schema(description = "User email address", example = "alex.smith@example.com")
    String email,

    @Schema(description = "User's phone number", example = "+1-555-0199")
    String phone,

    @Schema(description = "Account creation timestamp (ISO-8601 UTC)", example = "2026-08-23T10:00:00Z")
    Instant createdAt

) {}
```

### 4.7 Domain Event: `UserRegisteredEvent`
```java
package com.seatflow.user.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Domain event payload published when a user is registered via JIT provisioning")
public record UserRegisteredEvent(

    @Schema(description = "Internal user UUID")
    UUID userId,

    @Schema(description = "User email address")
    String email,

    @Schema(description = "Registration timestamp")
    Instant registeredAt

) implements DomainEvent {}
```

### 4.8 MapStruct Mapper: `UserMapper`
```java
package com.seatflow.user.mapper;

import com.seatflow.user.model.entity.User;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserMapper {

    UserProfileResponse toResponse(User user);
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p01-002-domain-entities-repositories-and-dtos develop`
2. **Step 2 — User Entity:** Create `User.java` JPA entity with lean fields (`externalId`, `email`, `phone`), explicit Lombok annotations, `@PrePersist`, `@PreUpdate`.
3. **Step 3 — OutboxEvent Entity:** Create `OutboxEvent.java` JPA entity with `jsonb` payload column.
4. **Step 4 — UserRepository:** Create Spring Data JPA repository with `findByExternalId`, `findByEmail` query methods.
5. **Step 5 — OutboxEventRepository:** Create repository with `findTop50ByPublishedAtIsNullOrderByCreatedAtAsc()`.
6. **Step 6 — Request DTO:** Create `UpdateUserProfileRequest` record with `phone` validation.
7. **Step 7 — Response DTO:** Create `UserProfileResponse` record with `@Schema` annotations.
8. **Step 8 — Domain Event:** Create `UserRegisteredEvent` record implementing `DomainEvent`.
9. **Step 9 — UserMapper:** Create MapStruct mapper interface with `toResponse(User)` method.
10. **Step 10 — Verify Compilation:** Run `mvn clean compile -pl services/user-service -am` from `backend/`.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean compile -pl services/user-service -am
```
- [ ] All entities compile cleanly and MapStruct generates implementation class.
- [ ] No `@Data` annotation used anywhere.
- [ ] `User` entity has lean fields without redundant name columns.
- [ ] All DTO records are immutable with proper `@Schema` and validation annotations.
- [ ] `UserRegisteredEvent` implements `DomainEvent` from `common-events`.
- [ ] Task file is moved to `.ai/tasks/completed/phase-01-user-service/002-domain-entities-repositories-and-dtos.md`.
