# TASK-P06-002: Ticket Domain Entities, Repositories, DTOs & MapStruct Mappers

## 1. Task Metadata
- **Task ID:** `TASK-P06-002`
- **Git Branch:** `feat/p06-002-domain-entities-repositories-and-dtos`
- **Target Module:** `backend/services/ticket-service`
- **Phase:** `Phase 06 - Ticket & QR Code Service`
- **Related Specs:** `.ai/architecture/03-database-models.md` (Section 2.6), `.ai/architecture/06-api-contracts.md` (Section 2.6)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`, `.ai/decisions/ADR-004-stripe-tax-and-tax-inclusive-pricing.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the domain persistence entities, Spring Data JPA repositories, immutable Java Record DTOs, and MapStruct mappers for `ticket-service`. This task establishes the core data models, custom query methods, and mapping boundaries; services, REST controllers, QR/PDF renderers, and Kafka consumers will be built on these contracts in subsequent tasks.

### Critical Invariants to Enforce:
- [ ] Entities use explicit Lombok annotations (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@AllArgsConstructor`); **NEVER use `@Data` or Lombok `@EqualsAndHashCode` on JPA entities**.
- [ ] `Ticket` entity has `@Version Long version`, `@DynamicUpdate`, explicit `@Table` unique constraints (`uq_tickets_ticket_code`, `uq_tickets_reservation_seat`) and index definitions matching Flyway V1 DDL, and Hibernate-safe `equals()` / `hashCode()` based on `Hibernate.getClass(this)` and `getId()`.
- [ ] **ADR-001 (Guest Checkout):** `Ticket.userId` is a nullable `UUID`, while `customerEmail` is mandatory (`@Column(name = "customer_email", nullable = false)`).
- [ ] **ADR-004 (Fiscal Breakdown):** `price` represents the gross tax-inclusive amount charged, while `taxAmount` and `netAmount` store the fiscal breakdown components using `BigDecimal` with `@Column(precision = 10, scale = 2, nullable = false)`.
- [ ] **Gate Scanner Audit Log (ADR-002):** `TicketValidation` entity maps to `ticket_validations` recording scan attempts, scanner device IDs, and verification results (`ValidationResult`), with nullable `ticketId` for invalid/unrecognized scans.
- [ ] **Transactional Outbox:** `OutboxEvent` maps to `outbox_events` with `@JdbcTypeCode(SqlTypes.JSON)` for the JSONB payload.
- [ ] All request and response DTOs are Java 21 Records decorated with OpenAPI `@Schema` annotations and Jakarta Bean Validation constraints.
- [ ] MapStruct mappers configure `componentModel = MappingConstants.ComponentModel.SPRING` and `unmappedTargetPolicy = ReportingPolicy.ERROR`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/model/enums/TicketStatus.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/model/enums/ValidationResult.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/model/entity/Ticket.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/model/entity/TicketValidation.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/model/entity/OutboxEvent.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/repository/TicketRepository.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/repository/TicketValidationRepository.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/repository/OutboxEventRepository.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/dto/request/ValidateTicketRequest.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/dto/response/TicketResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/dto/response/TicketDetailResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/dto/response/TicketSummaryResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/dto/response/ValidationResultResponse.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/mapper/TicketMapper.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/repository/TicketRepositoryTest.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/repository/TicketValidationRepositoryTest.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/mapper/TicketMapperTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Enums
Create in `com.seatflow.ticket.model.enums`:

#### `TicketStatus.java`
```java
package com.seatflow.ticket.model.enums;

public enum TicketStatus {
    VALID,
    USED,
    CANCELLED
}
```

#### `ValidationResult.java`
```java
package com.seatflow.ticket.model.enums;

public enum ValidationResult {
    SUCCESS,
    ALREADY_USED,
    INVALID,
    CANCELLED
}
```

---

### 4.2 JPA Entities

#### `Ticket.java`
```java
package com.seatflow.ticket.model.entity;

import com.seatflow.ticket.model.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "tickets",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_tickets_ticket_code", columnNames = {"ticket_code"}),
        @UniqueConstraint(name = "uq_tickets_reservation_seat", columnNames = {"reservation_id", "seat_id"})
    },
    indexes = {
        @Index(name = "idx_tickets_event_status", columnList = "event_id, status"),
        @Index(name = "idx_tickets_user_id", columnList = "user_id"),
        @Index(name = "idx_tickets_customer_email", columnList = "customer_email"),
        @Index(name = "idx_tickets_reservation_id", columnList = "reservation_id"),
        @Index(name = "idx_tickets_payment_id", columnList = "payment_id")
    }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID reservationId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID paymentId;

    @Column(name = "user_id", updatable = true)
    @ToString.Include
    private UUID userId; // Nullable for guest checkouts (ADR-001)

    @Column(name = "customer_email", nullable = false, updatable = false)
    @ToString.Include
    private String customerEmail;

    @Column(name = "attendee_name")
    private String attendeeName;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "seat_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID seatId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price; // Gross tax-inclusive amount

    @Column(name = "tax_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO; // Tax portion (ADR-004)

    @Column(name = "net_amount", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal netAmount = BigDecimal.ZERO; // Net base price (ADR-004)

    @Column(name = "ticket_code", nullable = false, unique = true, length = 64, updatable = false)
    @ToString.Include
    private String ticketCode;

    @Column(name = "qr_code_data", nullable = false, columnDefinition = "TEXT")
    private String qrCodeData;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @ToString.Include
    private TicketStatus status = TicketStatus.VALID;

    @Version
    @Column(nullable = false)
    private Long version;

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
        Ticket ticket = (Ticket) o;
        return getId() != null && Objects.equals(getId(), ticket.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

#### `TicketValidation.java`
```java
package com.seatflow.ticket.model.entity;

import com.seatflow.ticket.model.enums.ValidationResult;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
    name = "ticket_validations",
    indexes = {
        @Index(name = "idx_validations_ticket_id", columnList = "ticket_id, scanned_at"),
        @Index(name = "idx_validations_device", columnList = "scanner_device_id, scanned_at")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class TicketValidation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "ticket_id", updatable = false)
    @ToString.Include
    private UUID ticketId; // Nullable for invalid/unrecognized QR scans

    @Column(name = "scanner_device_id", nullable = false, length = 100, updatable = false)
    @ToString.Include
    private String scannerDeviceId;

    @Column(name = "scan_result", nullable = false, length = 30, updatable = false)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private ValidationResult scanResult;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String details;

    @CreationTimestamp
    @Column(name = "scanned_at", nullable = false, updatable = false)
    @ToString.Include
    private Instant scannedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
        TicketValidation that = (TicketValidation) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

#### `OutboxEvent.java`
```java
package com.seatflow.ticket.model.entity;

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
        @Index(name = "idx_ticket_outbox_unpub", columnList = "created_at"),
        @Index(name = "idx_ticket_outbox_aggregate", columnList = "aggregate_id, created_at")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
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

### 4.3 Spring Data JPA Repositories

#### `TicketRepository.java`
```java
package com.seatflow.ticket.repository;

import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.model.enums.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByTicketCode(String ticketCode);

    Optional<Ticket> findByIdAndUserId(UUID id, UUID userId);

    Page<Ticket> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Ticket> findByReservationId(UUID reservationId);

    List<Ticket> findByPaymentId(UUID paymentId);

    boolean existsByPaymentId(UUID paymentId);

    boolean existsByEventIdAndSeatIdAndStatus(UUID eventId, UUID seatId, TicketStatus status);

    @Modifying
    @Query("UPDATE Ticket t SET t.userId = :userId WHERE t.customerEmail = :customerEmail AND t.userId IS NULL")
    int updateUserIdByCustomerEmailAndUserIdIsNull(@Param("userId") UUID userId, @Param("customerEmail") String customerEmail);
}
```

#### `TicketValidationRepository.java`
```java
package com.seatflow.ticket.repository;

import com.seatflow.ticket.model.entity.TicketValidation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TicketValidationRepository extends JpaRepository<TicketValidation, UUID> {

    List<TicketValidation> findByTicketIdOrderByScannedAtDesc(UUID ticketId);

    List<TicketValidation> findByScannerDeviceIdOrderByScannedAtDesc(String scannerDeviceId, Pageable pageable);
}
```

#### `OutboxEventRepository.java`
```java
package com.seatflow.ticket.repository;

import com.seatflow.ticket.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

    long countByPublishedAtIsNull();
}
```

---

### 4.4 DTO Records

#### `ValidateTicketRequest.java`
```java
package com.seatflow.ticket.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request body for validating ticket QR code at venue entrance")
public record ValidateTicketRequest(

    @Schema(description = "Unique ticket verification code", example = "SF-TKT-9876-ABCD")
    @NotBlank(message = "Ticket code is required")
    String ticketCode,

    @Schema(description = "Identifier of scanning device", example = "GATE-SOUTH-SCANNER-01")
    @NotBlank(message = "Scanner device ID is required")
    String scannerDeviceId

) {}
```

#### `TicketResponse.java`
```java
package com.seatflow.ticket.web.dto.response;

import com.seatflow.ticket.model.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Standard ticket response")
public record TicketResponse(
    @Schema(description = "Unique ticket ID") UUID id,
    @Schema(description = "Reservation ID") UUID reservationId,
    @Schema(description = "Payment ID") UUID paymentId,
    @Schema(description = "User ID (null for guests)") UUID userId,
    @Schema(description = "Customer email") String customerEmail,
    @Schema(description = "Attendee name") String attendeeName,
    @Schema(description = "Event ID") UUID eventId,
    @Schema(description = "Seat ID") UUID seatId,
    @Schema(description = "Total gross ticket price") BigDecimal price,
    @Schema(description = "Tax / VAT portion included") BigDecimal taxAmount,
    @Schema(description = "Net ticket base price") BigDecimal netAmount,
    @Schema(description = "Ticket code") String ticketCode,
    @Schema(description = "Ticket status") TicketStatus status,
    @Schema(description = "Creation timestamp") Instant createdAt
) {}
```

#### `TicketDetailResponse.java`
```java
package com.seatflow.ticket.web.dto.response;

import com.seatflow.ticket.model.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Detailed ticket response including QR code payload")
public record TicketDetailResponse(
    @Schema(description = "Unique ticket ID") UUID id,
    @Schema(description = "Reservation ID") UUID reservationId,
    @Schema(description = "Payment ID") UUID paymentId,
    @Schema(description = "User ID (null for guests)") UUID userId,
    @Schema(description = "Customer email") String customerEmail,
    @Schema(description = "Attendee name") String attendeeName,
    @Schema(description = "Event ID") UUID eventId,
    @Schema(description = "Seat ID") UUID seatId,
    @Schema(description = "Total gross ticket price") BigDecimal price,
    @Schema(description = "Tax / VAT portion included") BigDecimal taxAmount,
    @Schema(description = "Net ticket base price") BigDecimal netAmount,
    @Schema(description = "Ticket code") String ticketCode,
    @Schema(description = "QR code payload data") String qrCodeData,
    @Schema(description = "Ticket status") TicketStatus status,
    @Schema(description = "Creation timestamp") Instant createdAt,
    @Schema(description = "Update timestamp") Instant updatedAt
) {}
```

#### `TicketSummaryResponse.java`
```java
package com.seatflow.ticket.web.dto.response;

import com.seatflow.ticket.model.enums.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Compact ticket summary response")
public record TicketSummaryResponse(
    @Schema(description = "Unique ticket ID") UUID id,
    @Schema(description = "Ticket code") String ticketCode,
    @Schema(description = "Event ID") UUID eventId,
    @Schema(description = "Seat ID") UUID seatId,
    @Schema(description = "Ticket status") TicketStatus status,
    @Schema(description = "Ticket gross price") BigDecimal price,
    @Schema(description = "Creation timestamp") Instant createdAt
) {}
```

#### `ValidationResultResponse.java`
```java
package com.seatflow.ticket.web.dto.response;

import com.seatflow.ticket.model.enums.ValidationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Gate scanner verification outcome")
public record ValidationResultResponse(
    @Schema(description = "Whether the ticket is valid for entry") boolean valid,
    @Schema(description = "Ticket ID") UUID ticketId,
    @Schema(description = "Ticket verification code") String ticketCode,
    @Schema(description = "Validation result status") ValidationResult result,
    @Schema(description = "Event title") String eventTitle,
    @Schema(description = "Event date") Instant eventDate,
    @Schema(description = "Attendee name") String attendeeName,
    @Schema(description = "Venue section name") String section,
    @Schema(description = "Seat row label") String rowNumber,
    @Schema(description = "Seat number") Integer seatNumber,
    @Schema(description = "Scan timestamp") Instant scannedAt,
    @Schema(description = "Descriptive outcome message") String message
) {}
```

---

### 4.5 MapStruct Mapper Contract

#### `TicketMapper.java`
```java
package com.seatflow.ticket.mapper;

import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import com.seatflow.ticket.web.dto.response.TicketSummaryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface TicketMapper {

    TicketResponse toResponse(Ticket ticket);

    TicketDetailResponse toDetailResponse(Ticket ticket);

    TicketSummaryResponse toSummaryResponse(Ticket ticket);

    List<TicketResponse> toResponseList(List<Ticket> tickets);
}
```

---

### 4.6 Repository Slice Tests Contract
`TicketRepositoryTest`:
- Uses `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)`, `@Testcontainers`.
- Tests saving `Ticket` entity with nullable `userId` and asserts persistence.
- Tests `updateUserIdByCustomerEmailAndUserIdIsNull` (asserts that guest tickets with `userId = null` are linked to registered user ID upon query execution).
- Tests `findByTicketCode`, `findByUserIdOrderByCreatedAtDesc`, `existsByPaymentId`.
- Tests that saving two `VALID` tickets for the same `(eventId, seatId)` throws `DataIntegrityViolationException` due to partial unique index `uq_tickets_event_seat_valid`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p06-002-domain-entities-repositories-and-dtos` from `develop`.
2. Implement enums `TicketStatus` and `ValidationResult`.
3. Implement JPA entities `Ticket`, `TicketValidation`, `OutboxEvent` with exact `@Table` definitions, Hibernate-safe `equals()` / `hashCode()`, and `@ToString` safety.
4. Implement repositories `TicketRepository`, `TicketValidationRepository`, and `OutboxEventRepository`.
5. Implement DTO records (`ValidateTicketRequest`, `TicketResponse`, `TicketDetailResponse`, `TicketSummaryResponse`, `ValidationResultResponse`).
6. Implement `TicketMapper` and its unit test.
7. Write `TicketRepositoryTest` and `TicketValidationRepositoryTest` with Testcontainers.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/ticket-service -Dtest=*RepositoryTest,*MapperTest
```

- [ ] All entities, repositories, DTOs, and mappers compile cleanly.
- [ ] Repository slice tests pass against PostgreSQL 16 Testcontainer.
- [ ] Guest claiming query `updateUserIdByCustomerEmailAndUserIdIsNull` verified in tests.
- [ ] Partial unique index `uq_tickets_event_seat_valid` verified in tests.
- [ ] Task file is moved to `.ai/tasks/completed/phase-06-ticket-service/002-domain-entities-repositories-and-dtos.md` when complete.
