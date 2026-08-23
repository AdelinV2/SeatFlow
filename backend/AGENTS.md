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

## 3. 7-Step Implementation Sequence

Follow this strict sequence for every backend task without skipping steps:

```
1. Read the assigned task file from .ai/tasks/phase-X/XXX-task.md.
2. Identify target microservice and check shared abstractions in backend/common/:
   - common-domain (Base exceptions, ErrorCode, ApiErrorResponse, PagedResult)
   - common-events (EventEnvelope<T>, DomainEvent, EventHeaders, EventTopics)
   - common-observability (GlobalExceptionHandler, CorrelationIdFilter)
   - common-security (JwtRoleConverter, SecurityRoles, UserContext)
3. Implement in this exact sequence:
   a. Flyway migration (resources/db/migration/)
   b. Entity (model/entity/)
   c. Repository (repository/)
   d. Request/Response Records (web/dto/request/, web/dto/response/)
   e. Mapper (mapper/)
   f. Service interface (service/)
   g. Service implementation (service/impl/)
   h. Controller (web/controller/)
   i. Tests
4. Run verification command specified in the task file (e.g. mvn test).
```

---

## 4. Microservice Package Structure

Every business microservice must follow this exact package layout:

```
<service-name>/
├── pom.xml
├── Dockerfile
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
    │   │   └── client/            # RestClient / FeignClient for internal REST calls
    │   └── resources/
    │       ├── application.yaml          # Base config (zero secrets)
    │       ├── application-dev.yaml      # Development overrides (local Postgres/Kafka)
    │       ├── application-test.yaml     # Test overrides (Testcontainers dynamic properties)
    │       └── db/migration/             # Flyway migrations: V1__description.sql
    └── test/
        └── java/com/seatflow/<service>/
            ├── service/           # Unit tests with Mockito
            ├── repository/        # Repository tests with @DataJpaTest + Testcontainers
            ├── web/               # Controller slice tests with @WebMvcTest
            ├── messaging/         # Kafka consumer/producer tests
            └── integration/       # End-to-end service tests with Testcontainers
```

**Service-specific modules:**
```
scheduler/      → Reservation hold expiration sweeper (Reservation Service)
stripe/         → Stripe payment gateway adapter & webhook signature verifier (Payment Service)
qr/             → ZXing QR code generator & PDF renderer (Ticket Service)
websocket/      → WebSocket STOMP handlers (Realtime Service)
```

---

## 5. Layer-by-Layer Code Standards

### 5.1 JPA Entities

Use explicit Lombok annotations — **NEVER `@Data` on entities** (breaks JPA proxies, lazy loading, and `equals`/`hashCode`).

```java
@Entity
@Table(name = "reservations")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(unique = true)
    private String idempotencyKey;

    @Version
    private Long version;  // for optimistic locking

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
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

Rules:
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)` — JPA requires it; never make it public.
- `@Version` — mandatory where optimistic concurrency control is required.
- `@Column(nullable = false)` — explicitly declare nullability on every column.
- `@Enumerated(EnumType.STRING)` — always STRING, never ORDINAL.
- `GenerationType.UUID` — standard primary key strategy across all microservices.

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
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,
    CONSTRAINT max_retries CHECK (retry_count <= 5)
);
CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL;
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

## 8. Resilience & Security

### 8.1 Resilience4j
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

### 8.2 Security & User Extraction
Extract claims cleanly via `UserContext`:
```java
String userId = UserContext.getCurrentUserId()
        .orElseThrow(() -> new BusinessException("Unauthorized user", ErrorCode.UNAUTHORIZED, 401));
boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);
```

---

## 9. Testing Standards & Testcontainers

### 9.1 Repository Slice Testing (`@DataJpaTest`)
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

### 9.2 High-Concurrency Stress Testing (CountDownLatch)
Mandatory for testing seat hold race conditions:

```java
@Test
void shouldPreventDoubleBookingUnderConcurrentLoad() throws InterruptedException {
    UUID seatId = UUID.randomUUID();
    int threads = 50;
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger conflictCount = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
        String userId = "user-" + i;
        executor.submit(() -> {
            try {
                latch.await();
                reservationService.createReservation(new CreateReservationRequest(eventId, List.of(seatId), "idem-" + userId), userId);
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

## 10. Backend Completion Checklist

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
- [ ] Zero secrets hardcoded.
