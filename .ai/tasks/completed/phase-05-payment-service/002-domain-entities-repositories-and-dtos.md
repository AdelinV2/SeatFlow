# TASK-P05-002: Payment Domain Entities, Repositories, DTOs & MapStruct Mappers

## 1. Task Metadata
- **Task ID:** `TASK-P05-002`
- **Git Branch:** `feat/p05-002-domain-entities-repositories-and-dtos`
- **Target Module:** `backend/services/payment-service`
- **Phase:** `Phase 05 - Payment & Stripe Service`
- **Related Specs:** `.ai/architecture/03-database-models.md` (Section 2.5), `.ai/architecture/06-api-contracts.md` (Section 2.5)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the domain persistence entities, Spring Data JPA repositories, immutable Java Record DTOs, and MapStruct mappers for the `payment-service`. This task establishes the core data models, custom query methods, and mapping boundaries; services, REST controllers, and Kafka consumers will be built on these contracts in subsequent tasks.

### Critical Invariants to Enforce:
- [ ] Entities use explicit Lombok annotations (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@AllArgsConstructor`); **NEVER use `@Data` or Lombok `@EqualsAndHashCode` on JPA entities**.
- [ ] `Payment` entity has `@Version Long version`, `@DynamicUpdate`, explicit `@Table` unique constraints (`uq_payments_idempotency_key`, `uq_payments_reservation_id`) and index definitions matching Flyway V1 DDL, and Hibernate-safe `equals()` / `hashCode()` based on `Hibernate.getClass(this)` and `getId()`.
- [ ] **ADR-001 (Guest Checkout):** `Payment.userId` is a nullable `UUID`, while `customerEmail` is mandatory (`@Column(name = "customer_email", nullable = false)`).
- [ ] **Stripe Intent & Idempotency Keys:** `stripePaymentIntentId` is mapped with updatable flexibility (can be assigned upon intent creation) and partial unique index matching Flyway V1 DDL. `idempotencyKey` is mandatory and immutable (`updatable = false`).
- [ ] **Financial Precision:** Currency amounts use `BigDecimal` with `@Column(precision = 10, scale = 2, nullable = false)`.
- [ ] All request and response DTOs are Java 21 Records decorated with OpenAPI `@Schema` annotations and Jakarta Bean Validation constraints.
- [ ] MapStruct mappers configure `componentModel = MappingConstants.ComponentModel.SPRING` and `unmappedTargetPolicy = ReportingPolicy.ERROR`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/model/enums/PaymentStatus.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/model/entity/Payment.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/model/entity/OutboxEvent.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/repository/PaymentRepository.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/repository/OutboxEventRepository.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/web/dto/request/CreatePaymentIntentRequest.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/web/dto/response/PaymentIntentResponse.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/web/dto/response/PaymentResponse.java`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/mapper/PaymentMapper.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/repository/PaymentRepositoryTest.java`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/mapper/PaymentMapperTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Enum Definition
Create `PaymentStatus` in `com.seatflow.payment.model.enums`:

```java
package com.seatflow.payment.model.enums;

public enum PaymentStatus {
    INITIATED,
    SUCCESS,
    FAILED,
    REFUNDED
}
```

---

### 4.2 JPA Entities

#### `Payment.java`
```java
package com.seatflow.payment.model.entity;

import com.seatflow.payment.model.enums.PaymentStatus;
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
    name = "payments",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_payments_idempotency_key", columnNames = {"idempotency_key"}),
        @UniqueConstraint(name = "uq_payments_reservation_id", columnNames = {"reservation_id"})
    },
    indexes = {
        @Index(name = "idx_payments_reservation_id", columnList = "reservation_id"),
        @Index(name = "idx_payments_user_id", columnList = "user_id"),
        @Index(name = "idx_payments_customer_email", columnList = "customer_email"),
        @Index(name = "idx_payments_status_created", columnList = "status, created_at"),
        @Index(name = "idx_payments_event_id", columnList = "event_id")
    }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID reservationId;

    @Column(name = "user_id")
    @ToString.Include
    private UUID userId; // Nullable for guest checkouts (ADR-001)

    @Column(name = "customer_email", nullable = false, updatable = false)
    @ToString.Include
    private String customerEmail;

    @Column(name = "event_id", nullable = false, updatable = false)
    @ToString.Include
    private UUID eventId;

    @Column(name = "stripe_payment_intent_id")
    @ToString.Include
    private String stripePaymentIntentId;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    @ToString.Include
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

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
        Payment payment = (Payment) o;
        return getId() != null && Objects.equals(getId(), payment.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

#### `OutboxEvent.java`
```java
package com.seatflow.payment.model.entity;

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
        @Index(name = "idx_pay_outbox_unpub", columnList = "created_at"),
        @Index(name = "idx_pay_outbox_aggregate", columnList = "aggregate_id, created_at")
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

#### `PaymentRepository.java`
```java
package com.seatflow.payment.repository;

import com.seatflow.payment.model.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByReservationId(UUID reservationId);

    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Payment> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Payment p SET p.userId = :userId, p.updatedAt = :now WHERE p.customerEmail = :email AND p.userId IS NULL")
    int updateUserIdForCustomerEmail(@Param("userId") UUID userId, @Param("email") String email, @Param("now") Instant now);
}
```

#### `OutboxEventRepository.java`
```java
package com.seatflow.payment.repository;

import com.seatflow.payment.model.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByAggregateIdAndEventType(UUID aggregateId, String eventType);

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
package com.seatflow.payment.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

@Schema(description = "Request body for creating a Stripe PaymentIntent for an active reservation")
public record CreatePaymentIntentRequest(

    @Schema(description = "UUID of the pending reservation to pay for", example = "123e4567-e89b-12d3-a456-426614174000", requiredMode = REQUIRED)
    @NotNull(message = "Reservation ID is required")
    UUID reservationId,

    @Schema(description = "Unique client-generated idempotency key to prevent double charges", example = "pay-req-user123-uuid-001", requiredMode = REQUIRED)
    @NotBlank(message = "Idempotency key is required")
    @Size(max = 255)
    String idempotencyKey
) {}
```

```java
package com.seatflow.payment.web.dto.response;

import com.seatflow.payment.model.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Response returned upon Stripe PaymentIntent creation with client secret for Stripe Elements")
public record PaymentIntentResponse(
    @Schema(description = "Payment aggregate identifier") UUID paymentId,
    @Schema(description = "Stripe client secret used by the frontend Stripe Elements SDK") String clientSecret,
    @Schema(description = "Total payment amount") BigDecimal amount,
    @Schema(description = "Payment currency ISO code", example = "USD") String currency,
    @Schema(description = "Current payment status") PaymentStatus status
) {}
```

```java
package com.seatflow.payment.web.dto.response;

import com.seatflow.payment.model.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Detailed payment status response")
public record PaymentResponse(
    @Schema(description = "Unique payment identifier") UUID id,
    @Schema(description = "Target reservation identifier") UUID reservationId,
    @Schema(description = "Customer user identifier (null for guests)") UUID userId,
    @Schema(description = "Customer email address") String customerEmail,
    @Schema(description = "Target event identifier") UUID eventId,
    @Schema(description = "Stripe PaymentIntent ID") String stripePaymentIntentId,
    @Schema(description = "Payment amount") BigDecimal amount,
    @Schema(description = "Payment currency ISO code") String currency,
    @Schema(description = "Payment status") PaymentStatus status,
    @Schema(description = "Failure reason if payment was unsuccessful") String failureReason,
    @Schema(description = "Payment creation timestamp") Instant createdAt,
    @Schema(description = "Payment last updated timestamp") Instant updatedAt
) {}
```

---

### 4.5 MapStruct Mapper Contract

```java
package com.seatflow.payment.mapper;

import com.seatflow.payment.model.entity.Payment;
import com.seatflow.payment.web.dto.response.PaymentIntentResponse;
import com.seatflow.payment.web.dto.response.PaymentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface PaymentMapper {

    PaymentResponse toResponse(Payment payment);

    @Mapping(target = "paymentId", source = "payment.id")
    @Mapping(target = "clientSecret", source = "clientSecret")
    @Mapping(target = "amount", source = "payment.amount")
    @Mapping(target = "currency", source = "payment.currency")
    @Mapping(target = "status", source = "payment.status")
    PaymentIntentResponse toIntentResponse(Payment payment, String clientSecret);
}
```

---

### 4.6 Repository Slice & Mapper Tests Contract
- `PaymentRepositoryTest`: `@DataJpaTest`, `@ActiveProfiles("test")`, `@Testcontainers` with PostgreSQL 16.
  - Asserts `findByIdempotencyKey` lookup and unique constraint violation on duplicate key.
  - Asserts `findByReservationId` unique constraint violation when persisting two payments for the same `reservationId`.
  - Asserts `findByStripePaymentIntentId` lookup and partial unique index `uq_payments_stripe_intent`.
  - Asserts `updateUserIdForCustomerEmail` updates historical guest payments with `userId = null` to the provided `userId`.
- `PaymentMapperTest`: Unit test verifying conversion between `Payment` entity, `PaymentResponse`, and `PaymentIntentResponse`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p05-002-domain-entities-repositories-and-dtos` from `develop`.
2. Implement enum `PaymentStatus`.
3. Create JPA entities `Payment` and `OutboxEvent` adhering strictly to explicit Lombok annotations and Hibernate-safe equality.
4. Implement repositories `PaymentRepository` and `OutboxEventRepository` with custom queries.
5. Create all immutable DTO records with Jakarta Validation and OpenAPI `@Schema`.
6. Implement MapStruct mapper `PaymentMapper`.
7. Write PostgreSQL Testcontainers repository slice tests and mapper unit tests.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/payment-service -Dtest=PaymentRepositoryTest,PaymentMapperTest
```

- [ ] All entity definitions, constraints, and column mappings match Flyway V1 and V2 DDL.
- [ ] Single payment per reservation constraint `uq_payments_reservation_id` and partial unique index `uq_payments_stripe_intent` are verified via `@DataJpaTest`.
- [ ] MapStruct mapper compiles with `ReportingPolicy.ERROR` and zero unmapped fields.
- [ ] Task file is moved to `.ai/tasks/completed/phase-05-payment-service/002-domain-entities-repositories-and-dtos.md` when complete.
