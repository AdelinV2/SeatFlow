# TASK-P08-002: Domain Entities, Enums, Repositories, DTOs & MapStruct Mappers

## 1. Task Metadata
- **Task ID:** `TASK-P08-002`
- **Git Branch:** `feat/p08-001-notification-service`
- **Target Module:** `backend/services/notification-service`
- **Phase:** `Phase 08 - Notification Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/03-database-models.md` (Section 2.7)
- **Related ADRs:** `ADR-002: Database Indexing and Integrity Standards`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the domain model layer for `notification-service`, including enums (`NotificationStatus`, `NotificationTemplateType`), the JPA entity `NotificationLog` with strict Hibernate-safe `equals()`/`hashCode()` and `@DynamicUpdate`, `NotificationLogRepository` with custom queries (including `SELECT ... FOR UPDATE SKIP LOCKED` for failed retries), immutable Java record DTOs (`NotificationLogResponse`, `EmailAttachmentDto`, `SendEmailCommand`), and MapStruct mappers (`NotificationMapper`).

### Critical Invariants to Enforce:
- [x] **No `@Data` on JPA Entities:** Always use explicit `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@AllArgsConstructor`, `@ToString(onlyExplicitlyIncluded = true)`.
- [x] **Hibernate-Safe `equals()`/`hashCode()`:** Implement `Hibernate.getClass(this) != Hibernate.getClass(o)` with null-safe `getId()` comparison. `hashCode()` returns `getClass().hashCode()`.
- [x] **Immutable Java Records for DTOs:** All request/response DTOs and command objects must be Java Records.
- [x] **Multi-Instance Retry Query:** `NotificationLogRepository` must define `findFailedNotificationsForRetry` using `SELECT ... FOR UPDATE SKIP LOCKED`.
- [x] **MapStruct Component Model:** Spring bean mapping with `unmappedTargetPolicy = ReportingPolicy.ERROR`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/model/enums/NotificationStatus.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/model/enums/NotificationTemplateType.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/model/entity/NotificationLog.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/repository/NotificationLogRepository.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/web/dto/response/NotificationLogResponse.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/web/dto/common/EmailAttachmentDto.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/model/common/SendEmailCommand.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/mapper/NotificationMapper.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/repository/NotificationLogRepositoryTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Enums
```java
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}

public enum NotificationTemplateType {
    TICKET_ISSUED,
    PAYMENT_FAILED,
    RESERVATION_HELD
}
```

### 4.2 Entity Definition (`NotificationLog.java`)
```java
@Entity
@Table(
    name = "notification_logs",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_notifications_idempotency", columnNames = {"idempotency_key"})
    },
    indexes = {
        @Index(name = "idx_notif_recipient_created", columnList = "recipient_email, created_at DESC")
    }
)
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @Column(name = "recipient_email", nullable = false, updatable = false)
    @ToString.Include
    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "template_type", nullable = false, updatable = false, length = 100)
    @ToString.Include
    private NotificationTemplateType templateType;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(name = "idempotency_key", unique = true, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @ToString.Include
    private NotificationStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

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
        NotificationLog that = (NotificationLog) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

---

## 5. Step-by-Step Implementation Sequence
1. Create `NotificationStatus` and `NotificationTemplateType` enums.
2. Create `NotificationLog` entity with explicit Lombok, `@DynamicUpdate`, and Hibernate-safe `equals()`/`hashCode()`.
3. Create `NotificationLogRepository` with `existsByIdempotencyKey`, `findByIdempotencyKey`, pagination methods, and `findFailedNotificationsForRetry` native query using `FOR UPDATE SKIP LOCKED`.
4. Create DTO records (`NotificationLogResponse`, `EmailAttachmentDto`, `SendEmailCommand`).
5. Create `NotificationMapper` interface using MapStruct.
6. Write `@DataJpaTest` `NotificationLogRepositoryTest` with PostgreSQL Testcontainers.

---

## 6. Definition of Done & Verification Command
```bash
mvn clean test -pl backend/services/notification-service -Dtest=NotificationLogRepositoryTest
```
