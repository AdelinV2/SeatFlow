# SeatFlow — Backend Engineering & Architecture Instructions

This file contains repository-level coding and implementation instructions for developers and agents working on the **SeatFlow Backend** (`backend/`).

The authoritative architecture contracts live in `.ai/architecture/` and the master specification is `.ai/SeatFlow-Architecture-and-Implementation-Spec.md`.

---

## 1. Backend Stack Reference

Always check the active `pom.xml` files for exact versions. If you are unsure of any API or annotation behavior in Spring Boot 4.x / Spring Framework 7, consult official documentation before writing code.

| Technology | Version | Official Documentation |
|---|---|---|
| **Java** | 21 (LTS) | https://docs.oracle.com/en/java/javase/21/ |
| **Spring Boot** | 4.1.x | https://docs.spring.io/spring-boot/docs/current/reference/html/ |
| **Spring Framework** | 7.x | https://docs.spring.io/spring-framework/docs/current/reference/html/ |
| **Spring Cloud** | 2025.1.x (Oakwood) | https://spring.io/projects/spring-cloud |
| **Spring Cloud Gateway** | 2025.1.x | https://docs.spring.io/spring-cloud-gateway/docs/current/reference/html/ |
| **Spring Cloud Netflix Eureka** | 2025.1.x | https://docs.spring.io/spring-cloud-netflix/docs/current/reference/html/ |
| **Spring Security** | 7.x (via SB4) | https://docs.spring.io/spring-security/reference/ |
| **Spring Data JPA** | 4.x (via SB4) | https://docs.spring.io/spring-data/jpa/docs/current/reference/html/ |
| **spring-kafka** | latest compatible with SB4 | https://docs.spring.io/spring-kafka/docs/current/reference/html/ |
| **springdoc-openapi** | 3.x | https://springdoc.org/ |
| **MapStruct** | 1.6.x | https://mapstruct.org/documentation/stable/reference/html/ |
| **Lombok** | 1.18.x | https://projectlombok.org/features/ |
| **Flyway** | latest compatible with SB4 | https://documentation.red-gate.com/flyway |
| **PostgreSQL JDBC** | via SB4 BOM | https://jdbc.postgresql.org/documentation/ |
| **Testcontainers** | latest compatible with SB4 | https://java.testcontainers.org/ |
| **ZXing** | 3.5.x | https://github.com/zxing/zxing |
| **Resilience4j** | 2.x | https://resilience4j.readme.io/ |

> **Rule:** Do not guess APIs. Spring Boot 4.x and Spring Framework 7 introduce breaking changes from 3.x (Jakarta EE 11 baseline, updated configuration properties, RestClient-first paradigms). Always verify against official documentation.

---

## 2. Java 21 Idioms & Language Standards

Write modern, clean, expressive Java 21:

1. **Java Records for DTOs and Payloads:** All request DTOs, response DTOs, Kafka event payloads, and intermediate tuples must be Java Records. Never create mutable classes for data transfer.
2. **Pattern Matching:** Use pattern matching for `switch` statements and `instanceof`:
   ```java
   return switch (status) {
       case PENDING -> handlePending(reservation);
       case CONFIRMED -> handleConfirmed(reservation);
       case CANCELLED, EXPIRED -> handleExpired(reservation);
   };
   ```
3. **Sealed Interfaces:** Use sealed interfaces for domain event types and closed result hierarchies:
   ```java
   public sealed interface DomainEvent permits ReservationCreatedEvent, PaymentCompletedEvent, TicketIssuedEvent {}
   ```
4. **Text Blocks:** Use text blocks (`"""..."""`) for multiline SQL scripts, JSON test payloads, and email templates.
5. **Sequenced Collections:** Use Java 21 sequenced collection methods (`getFirst()`, `getLast()`, `reversed()`) instead of manual index math.

---

## 3. Implementation Sequence & Workflow

Follow this strict sequence for every backend task without skipping steps:

```
0. Mandatory Branch Checkout:
   git checkout -b feat/p<XX>-<YYY>-<description> develop
1. Read the assigned task file from .ai/tasks/phase-XX-<phase-name>/<YYY>-<task-name>.md.
2. Identify target microservice and check shared abstractions in backend/common/:
   - common-domain (Base exceptions, ErrorCode, ApiErrorResponse, PagedResult)
   - common-events (EventEnvelope<T>, DomainEvent, EventHeaders, EventTopics)
   - common-observability (GlobalExceptionHandler, CorrelationIdFilter, MdcLoggingFilter)
   - common-security (JwtRoleConverter, SecurityRoles, UserContext)
3. Ensure local .env file exists in the service directory (copy from .env.example).
4. Implement in this exact sequence:
   a. Flyway migration (resources/db/migration/)
   b. Entity & Enums (model/entity/, model/enums/)
   c. Repository (repository/)
   d. Request/Response Records (web/dto/request/, web/dto/response/)
   e. Mapper (mapper/)
   f. Service interface (service/)
   g. Service implementation (service/impl/)
   h. Controller (web/controller/)
   i. Tests
5. Run verification command specified in the task file (e.g. mvn test).
```

---

## 4. Microservice Package Structure

Every business microservice must follow this exact package layout:

```
<service-name>/
├── pom.xml
├── Dockerfile
├── .env.example                       # Version-controlled template (dummy defaults)
├── .env                               # Local only (strictly .gitignored)
└── src/
    ├── main/
    │   ├── java/com/seatflow/<service>/
    │   │   ├── config/            # Spring beans, Kafka/Redis/client config, SecurityFilterChain
    │   │   ├── web/
    │   │   │   ├── controller/    # HTTP adapters only — NO business logic
    │   │   │   └── dto/
    │   │   │       ├── request/   # Java Records with @Schema + Jakarta Bean Validation
    │   │   │       └── response/  # Java Records with @Schema (ApiErrorResponse from common-domain)
    │   │   ├── service/           # Interfaces (contracts)
    │   │   │   └── impl/          # Implementations (@Service, @Transactional, @Slf4j)
    │   │   ├── repository/        # Spring Data JPA repositories
    │   │   ├── model/
    │   │   │   ├── entity/        # JPA entities with Lombok explicit annotations (NO @Data)
    │   │   │   ├── enums/         # Service-owned enums
    │   │   │   ├── common/        # Intra-service shared records/value objects
    │   │   │   └── valueobject/   # Domain value objects where meaningful
    │   │   ├── mapper/            # MapStruct mappers (componentModel = "spring")
    │   │   ├── messaging/
    │   │   │   ├── consumer/      # Kafka consumers (idempotent)
    │   │   │   ├── producer/      # Outbox publisher / Kafka producers
    │   │   │   └── event/         # Local event records (implementing DomainEvent)
    │   │   └── client/            # RestClient adapters for inter-service REST calls (Eureka + LoadBalancer)
    │   └── resources/
    │       ├── application.yaml          # Base configuration (common defaults, Jackson, OpenAPI, Actuator)
    │       ├── application-local.yaml     # Local IDE execution (connects to localhost:5432, ANSI console logs)
    │       ├── application-docker.yaml    # Docker Compose execution (connects to container hostnames: postgres, kafka)
    │       ├── application-prod.yaml      # GCP Cloud Run / GKE execution (GCP Secret Manager, Cloud SQL, JSON logs)
    │       ├── application-test.yaml      # Test execution profile (Testcontainers dynamic properties)
    │       ├── logback-spring.xml         # Logstash JSON logging encoder + masking rules
    │       └── db/migration/             # Flyway SQL migrations: V1__description.sql
    └── test/
        └── java/com/seatflow/<service>/
            ├── service/           # Unit tests with Mockito
            ├── repository/        # Repository tests with @DataJpaTest + Testcontainers
            ├── web/               # Controller slice tests with @WebMvcTest
            ├── messaging/         # Kafka consumer/producer tests
            └── integration/       # End-to-end service tests with Testcontainers
```

### 4.1 Spring Profiles & Runtime Environment Matrix

Every microservice resolves its active profile via the `SPRING_PROFILES_ACTIVE` environment variable:

| Profile (`SPRING_PROFILES_ACTIVE`) | Target Environment | Host Resolution (`DB_HOST`, `KAFKA_HOST`) | Logging Format | Security & Actuator |
|---|---|---|---|---|
| **`local`** (Default) | Standalone local IDE (IntelliJ / VS Code) | `localhost:5432`, `localhost:9092`, `localhost:6379` | ANSI Color Console | CORS allows `http://localhost:4200`, Actuator open |
| **`docker`** | Docker Compose Stack (`docker-compose up`) | `postgres:5432`, `kafka:9092`, `redis:6379`, `eureka-server:8761` | ANSI Color Console | CORS allows `http://localhost:4200`, Actuator open |
| **`prod`** | GCP Cloud Run / GKE (Staging & Production) | Cloud SQL Socket Factory / GCP Managed Services | Structured JSON (Logstash/ECS) | Strict CORS (`https://seatflow.app`), Actuator secured |
| **`test`** | Maven Build (`mvn test`) / CI Pipeline | Managed dynamically by Testcontainers | Minimal / Silent | In-memory / Mocked OAuth2 |

**Service-specific modules:**
```
scheduler/      → Reservation hold expiration sweeper (Reservation Service)
stripe/         → Stripe payment gateway adapter & webhook signature verifier (Payment Service)
qr/             → ZXing QR code generator & PDF renderer (Ticket Service)
websocket/      → WebSocket STOMP handlers (Realtime Service)
```

---

## 5. Layer-by-Layer Code Standards

### 5.1 JPA Entities & Database Schema Standards

Use explicit Lombok annotations — **NEVER `@Data` on entities** (breaks JPA proxies, lazy loading, and `equals`/`hashCode`).

Every JPA entity must declare its table name, explicit unique constraints, and indexes using `@Table` matching the Flyway DDL specifications (see [ADR-002](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md)):

```java
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
@DynamicUpdate // Generates targeted SQL UPDATEs for modified columns only, reducing lock contention
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

    @Column(name = "customer_name")
    private String customerName;

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

    @Version
    @Column(nullable = false)
    private Long version;  // for optimistic concurrency control

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
        Reservation that = (Reservation) o;
        return getId() != null && Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

#### DDL & Flyway Database Rules (ADR-002)
- **Primary Keys:** `UUID PRIMARY KEY DEFAULT gen_random_uuid()` named `pk_<table>`.
- **Foreign Keys:** Named `fk_<table>_<referenced_table>` with explicit `ON DELETE CASCADE` or `ON DELETE RESTRICT`.
- **Unique Constraints:** Named `uq_<table>_<column(s)>`.
- **Indexes:** Named `idx_<table>_<column(s)>`.
- **Partial Indexes:** Mandatory for high-frequency polling/scheduling queries (e.g. `idx_res_pending_expires_at ON reservations(expires_at ASC) WHERE status = 'PENDING'`).
- **Check Constraints:** Named `chk_<table>_<field_or_rule>` for business invariants (e.g. `chk_res_seat_count CHECK (seat_count >= 1 AND seat_count <= 10)`).
- **Optimistic Locking:** Mandatory `@Version private Long version;` on all mutable transaction roots (`events`, `reservations`, `payments`, `tickets`, `venues`).
- **Exception Mapping:** In `common-observability`, database integrity violations (`DataIntegrityViolationException` / PostgreSQL SQLState `23505`, `23503`, `23514`) are automatically mapped to `ConflictException` (`SEAT_ALREADY_RESERVED`, `DUPLICATE_RESOURCE`) or `ValidationException`.

#### Production Entity Standards Checklist:
1. **Hibernate-Safe `equals()` and `hashCode()` (NEVER Lombok `@EqualsAndHashCode` or `@Data` on JPA entities):**
   - Lombok generates strict `getClass() != o.getClass()` checks that break with Hibernate dynamic runtime proxies (lazy-loaded associations).
   - Lombok accesses fields directly instead of getters (`getId()`), returning uninitialized `null` on proxies.
   - Lombok's ID-based hashCode mutates when transient entities are persisted, corrupting `HashSet` and `HashMap` bucket structures.
   - **Mandatory Pattern:** Always implement explicit `equals(Object o)` with `Hibernate.getClass(this) != Hibernate.getClass(o)` and `getId()` null-safe comparison, paired with `hashCode()` returning `getClass().hashCode()`.
2. **Logging & ToString Safety:** Use `@ToString(onlyExplicitlyIncluded = true)` with `@ToString.Include` only on scalar identifiers/fields (never lazy relations) to avoid accidental lazy loading or N+1 queries during logger calls.
3. **`@DynamicUpdate`:** Enable on high-write / high-concurrency entities (`Reservation`, `Payment`, `Event`) to execute minimal SQL `UPDATE` statements containing only dirty columns.
4. **Flyway DDL Constraint Ownership (No Deprecated Annotations):** All database `CHECK` constraints (`chk_...`), foreign keys, and indexes are defined exclusively in Flyway SQL migrations (`db/migration/V...__...sql`). Never use deprecated vendor annotations like `@org.hibernate.annotations.Check` (deprecated since Hibernate 7). Enforce application-level constraints with Jakarta Bean Validation (`@NotNull`, `@Size`, `@Min`, `@Max`, `@Pattern`) in DTOs.
5. **JSONB Mapping:** Map PostgreSQL `JSONB` columns (such as `payload` in `OutboxEvent`) using `@JdbcTypeCode(SqlTypes.JSON)`.
6. **Column Immutability:** Explicitly declare `updatable = false` on immutable identifiers (`id`, `createdAt`, `idempotencyKey`, `eventId`, `userId`).
7. **Financial Precision:** Currency amounts must use `BigDecimal` with `@Column(precision = 10, scale = 2)`.
8. **Constructor Visibility:** Always `@NoArgsConstructor(access = AccessLevel.PROTECTED)` — required by JPA; never make it public.
9. **Enum Mapping:** Always `@Enumerated(EnumType.STRING)`, never `ORDINAL`.
10. **Primary Key Strategy:** Always `GenerationType.UUID`.


### 5.2 DTOs (Request / Response)

**All request and response DTOs must be Java Records**, never mutable classes.

```java
@Schema(description = "Request body for creating a seat reservation")
public record CreateReservationRequest(

    @Schema(description = "UUID of the target event", example = "123e4567-e89b-12d3-a456-426614174000")
    @NotNull(message = "Event ID is required")
    UUID eventId,

    @Schema(description = "List of seat IDs to hold. Maximum 10 seats per reservation.")
    @NotEmpty(message = "At least one seat must be selected")
    @Size(max = 10, message = "Maximum 10 seats per reservation")
    List<@NotNull UUID> seatIds,

    @Schema(description = "Idempotency key to prevent double submissions", example = "idem-98765-abcd")
    @NotBlank(message = "Idempotency key is required")
    String idempotencyKey

) {}

@Schema(description = "Seat reservation confirmation response")
public record ReservationResponse(
    @Schema(description = "Unique reservation ID") UUID id,
    @Schema(description = "Target event ID") UUID eventId,
    @Schema(description = "Current reservation status") ReservationStatus status,
    @Schema(description = "Reservation expiry timestamp (ISO-8601 UTC)") Instant expiresAt,
    @Schema(description = "List of held seats") List<SeatSummaryResponse> seats
) {}
```

### 5.3 REST Controllers

Controllers are pure HTTP adapters. No business logic, no raw entity exposures.

```java
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Tag(name = "Reservations", description = "Seat reservation and hold management APIs")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @Operation(
        summary = "Create a new seat reservation hold",
        description = "Places a temporary 15-minute hold on the selected seats. Maximum 10 seats allowed."
    )
    @ApiResponse(responseCode = "201", description = "Reservation hold created successfully",
        content = @Content(schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error or reservation seat limit exceeded",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "One or more seats are no longer available",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ReservationResponse> createReservation(
            @Valid @RequestBody CreateReservationRequest request) {
        String userId = UserContext.getCurrentUserId()
                .orElseThrow(() -> new BusinessException("User ID not found in token", ErrorCode.UNAUTHORIZED, 401));
        ReservationResponse response = reservationService.createReservation(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
```

Rules:
- Constructor injection with `@RequiredArgsConstructor` (never `@Autowired` on fields).
- `@Valid` on every `@RequestBody`.
- Always return `ResponseEntity<T>` with explicit HTTP status codes.
- Use `UserContext` (`common-security`) to extract authenticated principal details.

### 5.4 Service Layer

```java
public interface ReservationService {
    ReservationResponse createReservation(CreateReservationRequest request, String userId);
    ReservationResponse getReservationById(UUID reservationId, String userId);
    void cancelReservation(UUID reservationId, String userId);
}

@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;
    private final OutboxEventRepository outboxEventRepository;

    @Override
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request, String userId) {
        log.info("Processing reservation request: eventId={}, userId={}, seatCount={}",
                 request.eventId(), userId, request.seatIds().size());

        // Validate business invariants
        if (request.seatIds().size() > 10) {
            throw new ValidationException("Cannot reserve more than 10 seats", ErrorCode.MAX_SEATS_EXCEEDED);
        }

        // Domain logic and transactional state persistence
        // ...
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse getReservationById(UUID reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));
        return reservationMapper.toResponse(reservation);
    }
}
```

Rules:
- Interface in `service/`, implementation in `service/impl/`.
- `@Transactional` at method level — use `readOnly = true` on query methods.
- Log business milestones with meaningful context IDs; **never log passwords, tokens, or payment card details**.

### 5.5 MapStruct Mappers

```java
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReservationMapper {

    ReservationResponse toResponse(Reservation reservation);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "PENDING")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "version", ignore = true)
    Reservation toEntity(CreateReservationRequest request, UUID userId, Instant expiresAt);
}
```

Rules:
- `unmappedTargetPolicy = ReportingPolicy.ERROR` — compilation fails if any field is unmapped.
- `@Mapping(target = "field", ignore = true)` — explicitly document fields managed programmatically.
- Mappers are Spring beans (`componentModel = MappingConstants.ComponentModel.SPRING`).

### 5.6 Centralized Exception Handling

Global exception handling is centralized in **`common-observability`** (`GlobalExceptionHandler`), which is auto-configured for all services. Microservices **MUST NOT** define their own `@RestControllerAdvice` or custom error DTOs.

- `ConflictException` (409) — for double-booking or state conflicts (`ErrorCode.SEAT_ALREADY_RESERVED`).
- `ResourceNotFoundException` (404) — when entity does not exist (`ErrorCode.RESOURCE_NOT_FOUND`).
- `ValidationException` (400) — for business rule violations (`ErrorCode.MAX_SEATS_EXCEEDED`).
- `BusinessException` (Custom status) — for generic domain violations.

### 5.7 Synchronous Inter-Service REST Clients (Eureka + Spring Cloud LoadBalancer)

When a microservice needs to query or command another microservice synchronously over HTTP REST:

1. **Dependency:** Include `spring-cloud-starter-loadbalancer` in `pom.xml`:
   ```xml
   <dependency>
       <groupId>org.springframework.cloud</groupId>
       <artifactId>spring-cloud-starter-loadbalancer</artifactId>
   </dependency>
   ```
2. **RestClient Configuration (`config/RestClientConfig.java`):**
   - **Mandatory `@Primary` Plain Builder:** Always declare an un-annotated `@Primary` `RestClient.Builder`. Eureka Client internally uses `RestClient` to contact Eureka Server; if the only builder in the context were `@LoadBalanced`, Eureka would attempt to resolve its own server URL through the load balancer and fail registration.
   - **Load-Balanced Builder:** Declare a `@LoadBalanced` `RestClient.Builder` with a distinct bean qualifier for inter-service communication.
   ```java
   @Configuration
   public class RestClientConfig {

       @Bean
       @Primary
       public RestClient.Builder restClientBuilder() {
           return RestClient.builder();
       }

       @Bean
       @LoadBalanced
       public RestClient.Builder targetServiceLoadBalancedBuilder() {
           return RestClient.builder();
       }
   }
   ```
3. **Client Implementation (`client/impl/<Target>ClientImpl.java`):**
   - Inject the qualified `@LoadBalanced` `RestClient.Builder`.
   - Set the `baseUrl("http://" + serviceId)` using the target service's registered Spring Application name (e.g. `http://event-service`, `http://seat-map-service`).
   - Configure connection and read timeouts explicitly via `SimpleClientHttpRequestFactory` (e.g., connect timeout 3s, read timeout 5s).
   - Propagate `X-Correlation-Id` using a request interceptor reading from `CorrelationContext.getCorrelationId()`.
   - Guard inter-service executions with a Resilience4j `CircuitBreaker`.
   - Never hardcode hostnames (`localhost`) or static ports in client code or production configurations.

---

## 6. Concurrency, Locking & Invariant Sweeping

### 6.1 Concurrency & Zero Double-Booking
1. **Optimistic Locking:** Managed on `Reservation` via `@Version private Long version;`.
2. **Pessimistic Locking / DB Constraints:** On seat holds, use database unique constraints `UNIQUE (event_id, seat_id)` where status is active, combined with pessimistic write locks when querying hold availability:
   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("SELECT s FROM SeatHold s WHERE s.eventId = :eventId AND s.seatId IN :seatIds")
   List<SeatHold> findAndLockSeatsForUpdate(@Param("eventId") UUID eventId, @Param("seatIds") List<UUID> seatIds);
   ```

### 6.2 15-Minute Expiration Sweeper Job
In `reservation-service`, a scheduled sweeper releases expired holds without locking issues across multi-instance deployments:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationExpirationScheduler {

    private final ReservationRepository reservationRepository;
    private final OutboxEventRepository outboxEventRepository;

    @Scheduled(fixedDelayString = "${reservation.cleanup.interval-ms:30000}")
    @Transactional
    public void releaseExpiredReservations() {
        Instant now = Instant.now();
        List<Reservation> expired = reservationRepository.findExpiredReservationsForUpdate(now, Pageable.ofSize(100));
        for (Reservation res : expired) {
            res.setStatus(ReservationStatus.EXPIRED);
            reservationRepository.save(res);
            // Save ReservationExpired outbox event
        }
    }
}
```

Use `SELECT ... FOR UPDATE SKIP LOCKED` in the repository query to prevent deadlocks between instances:
```java
@Query(value = "SELECT * FROM reservations WHERE status = 'PENDING' AND expires_at < :now FOR UPDATE SKIP LOCKED", nativeQuery = true)
```

---

## 7. Transactional Outbox Pattern & Kafka Messaging

### 7.1 Outbox Schema & Publisher
Never publish directly to Kafka within a business transaction (`dual-write hazard`). Commit to `outbox_events` table in the same transaction:

```sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT chk_outbox_retry_count CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

Publisher Job:
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload());
                event.setPublishedAt(Instant.now());
                outboxRepository.save(event);
            } catch (Exception ex) {
                log.error("Failed to publish event: id={}, type={}", event.getId(), event.getEventType(), ex);
                event.setRetryCount(event.getRetryCount() + 1);
                outboxRepository.save(event);
            }
        }
    }
}
```

### 7.2 Idempotent Kafka Consumers
Every Kafka consumer must verify against the domain database or idempotency log before processing:

```java
@KafkaListener(topics = EventTopics.PAYMENT_COMPLETED, groupId = "ticket-service")
public void handlePaymentCompleted(EventEnvelope<PaymentCompletedEvent> envelope) {
    UUID paymentId = UUID.fromString(envelope.aggregateId());
    if (ticketRepository.existsByPaymentId(paymentId)) {
        log.info("Duplicate PaymentCompleted event skipped: eventId={}, paymentId={}", envelope.eventId(), paymentId);
        return;
    }
    // Process ticket generation
}
```

---

## 8. Enterprise Structured Logging & Prometheus Metrics

Logging in SeatFlow must be **production-grade, structured, and auditable**. Every log message must provide actionable operational context for Prometheus, Grafana, and Google Cloud Logging.

### 8.1 Structured Contextual Logging Standards

> **Rule:** Never write plain, uncontextualized logs like `log.info("User created with id " + id)` or `log.info("Seats held")`. Always log structured business events with all domain identifiers.

#### A. Structured Key-Value Business Logging Examples:
```java
// ✅ Production-Grade Business State Transition Log (INFO)
log.info("Seat hold acquired successfully. eventId={}, reservationId={}, userId={}, seatsCount={}, seatIds={}, totalAmount={}, expiresAt={}, durationMs={}",
        event.getId(), reservation.getId(), userId, seats.size(), seatIds, reservation.getTotalAmount(), reservation.getExpiresAt(), durationMs);

// ✅ Production-Grade Recoverable Conflict Log (WARN)
log.warn("Seat hold collision detected. eventId={}, requestedSeatIds={}, conflictingSeatIds={}, userId={}, clientIp={}",
        eventId, requestedSeatIds, conflictingSeatIds, userId, clientIp);

// ✅ Production-Grade Kafka Event Consumer Log (INFO)
log.info("Processing Kafka event. topic={}, eventType={}, eventId={}, aggregateId={}, correlationId={}, partition={}, offset={}",
        topic, envelope.eventType(), envelope.eventId(), envelope.aggregateId(), envelope.correlationId(), partition, offset);

// ✅ Production-Grade Webhook Verification Log (INFO / WARN)
log.warn("Stripe webhook duplicate event ignored. stripeEventId={}, paymentIntentId={}, status=ALREADY_PROCESSED",
        stripeEventId, paymentIntentId);

// ✅ Production-Grade Infrastructure / Database Failure Log (ERROR)
log.error("Failed to commit transactional outbox event. aggregateId={}, eventType={}, retryCount={}, durationMs={}",
        event.getAggregateId(), event.getEventType(), event.getRetryCount(), durationMs, exception);
```

#### B. Log Level Discipline:
| Level | When to Use | Example |
|---|---|---|
| **`DEBUG`** | Fine-grained developer information, internal SQL parameters, intermediate algorithm steps. | `log.debug("Evaluating pricing tier for section={}, seat={}", sectionId, seatId);` |
| **`INFO`** | Authoritative business lifecycle events, state changes, external integration milestones. | `log.info("Reservation confirmed. reservationId={}, paymentId={}", resId, payId);` |
| **`WARN`** | Recoverable business conflicts, expected race conditions, expired token lookups, duplicate webhooks. | `log.warn("Attempt to reserve already held seat. seatId={}, heldUntil={}", seatId, exp);` |
| **`ERROR`** | Unhandled exceptions, infrastructure failures, database deadlocks, Kafka publishing drops (always include exception). | `log.error("Outbox publishing failed for eventId={}", id, ex);` |

#### C. MDC (Mapped Diagnostic Context) Attributes:
Auto-injected into SLF4J MDC by `common-observability` and propagated across Kafka headers:
- `traceId` / `spanId` — W3C Distributed trace identifier.
- `correlationId` — End-to-end request identifier across microservices.
- `userId` — Authenticated user UUID extracted from JWT.
- `serviceName` — Name of the active microservice (e.g. `reservation-service`).
- `httpMethod` & `uri` — Active HTTP route (e.g. `POST /api/reservations`).
- `clientIp` — Remote client IP address.

### 8.2 Prometheus & Micrometer Metrics Instrumentation

Instrument key domain operations with dimensional tags:

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceImpl implements ReservationService {

    private final MeterRegistry meterRegistry;
    private final ReservationRepository reservationRepository;

    @Override
    @Transactional
    public ReservationResponse holdSeats(CreateReservationRequest request, String userId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Business Logic ...
            
            // Record Success Metric with Dimensional Tags
            meterRegistry.counter("seatflow.reservations.created.total",
                    "eventId", request.eventId().toString(),
                    "seatsCount", String.valueOf(request.seatIds().size()),
                    "status", "SUCCESS"
            ).increment();

            return response;
        } catch (ConflictException ex) {
            meterRegistry.counter("seatflow.reservations.conflicts.total",
                    "eventId", request.eventId().toString(),
                    "reason", ex.getErrorCode().name()
            ).increment();
            throw ex;
        } finally {
            sample.stop(meterRegistry.timer("seatflow.reservations.hold.duration",
                    "eventId", request.eventId().toString()));
        }
    }
}
```

---

## 9. Resilience & Security

### 9.1 Resilience4j
Configure circuit breakers and retries for synchronous inter-service communication:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      eventService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10000ms
```

### 9.2 Security & User Extraction
Extract claims cleanly via `UserContext`:
```java
String userId = UserContext.getCurrentUserId()
        .orElseThrow(() -> new BusinessException("Unauthorized user", ErrorCode.UNAUTHORIZED, 401));
boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);
```

---

## 10. Testing Standards & Testcontainers

### 10.1 Repository Slice Testing (`@DataJpaTest`)
```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ReservationRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

### 10.2 Service Slice Unit Testing (`@ExtendWith(MockitoExtension.class)`)
```java
@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @InjectMocks
    private ReservationServiceImpl reservationService;

    @Mock
    private ReservationRepository reservationRepository;

    @Test
    void shouldRejectReservationWhenMaxSeatsExceeded() {
        CreateReservationRequest request = new CreateReservationRequest(
                UUID.randomUUID(),
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID()), // 11 seats
                "idemp-key-1"
        );

        assertThatThrownBy(() -> reservationService.holdSeats(request, "user-123"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Maximum 10 seats allowed per reservation");
    }
}
```

### 10.3 Concurrency Test for Double-Booking Prevention
```java
@Test
void shouldPreventConcurrentDoubleBooking() throws InterruptedException {
    int threads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger conflictCount = new AtomicInteger();

    UUID eventId = UUID.randomUUID();
    UUID seatId = UUID.randomUUID();

    for (int i = 0; i < threads; i++) {
        final String userId = "user-" + i;
        executor.submit(() -> {
            try {
                latch.await();
                reservationService.holdSeats(new CreateReservationRequest(eventId, List.of(seatId), UUID.randomUUID().toString()), userId);
                successCount.incrementAndGet();
            } catch (ConflictException e) {
                conflictCount.incrementAndGet();
            } catch (Exception e) {
                log.error("Unexpected error", e);
            }
        });
    }

    latch.countDown();
    executor.shutdown();
    executor.awaitTermination(15, TimeUnit.SECONDS);

    assertThat(successCount.get()).isEqualTo(1);
    assertThat(conflictCount.get()).isEqualTo(threads - 1);
}
```

---

## 11. Backend Completion Checklist

A backend task is complete only when all items are verified:

- [ ] All code conforms to Java 21 standards (Records for DTOs, Pattern Matching).
- [ ] Flyway migration added in `resources/db/migration/` (if schema changed) with foreign key indexes.
- [ ] JPA entities use explicit Lombok (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`); NO `@Data`.
- [ ] Optimistic locking `@Version` added where concurrency is managed.
- [ ] DTOs are Java Records with full `@Schema` and Jakarta Bean Validation.
- [ ] Controllers documented with Swagger/OpenAPI annotations and return `ResponseEntity<T>`.
- [ ] Service interfaces separated from implementations.
- [ ] Outbox pattern used for domain events (NO direct `kafkaTemplate.send` in business transaction).
- [ ] Kafka consumers are idempotent.
- [ ] Unit tests (Mockito), repository tests (Testcontainers), and concurrency tests pass locally.
- [ ] Synchronous inter-service REST clients use Eureka + Spring Cloud LoadBalancer (`@LoadBalanced RestClient.Builder`) with a `@Primary` plain builder, timeout settings, and Resilience4j circuit breaking.
- [ ] Zero secrets hardcoded.
