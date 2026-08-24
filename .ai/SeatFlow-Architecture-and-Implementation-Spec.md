# SeatFlow — Architecture, Product Specification & Implementation Blueprint

**Document type:** Master technical specification / implementation blueprint  
**Project:** SeatFlow  
**Target:** Portfolio-grade, production-oriented event ticketing and real-time seat reservation platform  
**Primary stack:** Java 21 (LTS), Spring Boot 4.1.x (Spring Framework 7), Angular 22, PostgreSQL, Redis, Kafka  
**Architecture:** Microservices, service discovery with Eureka, event-driven, REST + WebSocket  
**Deployment target:** Google Cloud for production; Docker Compose for local development  
**Service discovery:** Netflix Eureka Server + Spring Cloud Eureka Clients (Spring Cloud 2025.1.x — Oakwood)  
**Authentication:** Microsoft Entra External ID with OIDC, Google federation, email/password  
**AI:** Optional Phase 2 using Spring AI + MCP/tool calling  

---

## 0. Purpose of This Document

This document is the authoritative functional and technical specification for SeatFlow. It is the project-level source of truth for product scope, architecture, domain behavior, infrastructure, implementation sequencing, and delivery criteria. Repository-level agent behavior and coding rules live separately in `AGENTS.md` and must not be embedded in this product specification.

The project is intentionally **small as a product and deep as an engineering exercise**. We do not want a huge feature set. We want a focused ticketing application that demonstrates strong Java backend engineering, distributed systems, concurrency control, asynchronous messaging, real-time communication, testing, observability, security, cloud deployment, and CI/CD.

---

# 1. Product Vision

SeatFlow is an online event ticketing platform inspired by real-world theatre/concert ticketing experiences.

A customer should be able to:

1. Browse upcoming events from a visually attractive home page.
2. Open an event and see its date, venue, description, pricing and seat layout.
3. Select one or multiple available seats.
4. Confirm the selection.
5. Have those seats temporarily held for **15 minutes**.
6. Complete a simulated payment.
7. Receive a purchase confirmation by email.
8. Receive one or more digital tickets containing QR codes.
9. View historical and upcoming purchases in a simple account area.
10. See seat availability change in real time without refreshing the page.

An administrator should be able to:

1. Create and manage events.
2. Associate an event with a venue/seat map.
3. Configure seat categories and prices.
4. View reservations and ticket sales.

The future AI extension should allow a customer to ask for actions such as:

> “Reserve the best available student seat for Hamlet, as close to the stage as possible.”

The AI must use controlled backend tools, not direct database access, and actions that create reservations must require explicit confirmation before finalizing a payment.

---

# 2. Core Design Principles

## 2.1 Small product, serious engineering

Do not grow the application by adding dozens of business features. Add depth through engineering quality.

## 2.2 Production-oriented, not production theater

Every technology must solve a real problem.

Examples:

- Kafka: asynchronous backend events and decoupling.
- Redis: temporary reservation state, caching and coordination where justified.
- WebSocket: live seat availability updates.
- PostgreSQL: transactional source of truth.
- Outbox Pattern: reliable publication of domain events.
- Testcontainers: realistic integration tests.
- OpenTelemetry: distributed tracing.
- Prometheus/Grafana: operational metrics.
- OIDC: externalized identity management.

Do not add infrastructure solely because it looks impressive.

## 2.3 Correctness before optimization

Seat ownership, payment state, reservation expiration and ticket validity must be correct even when requests arrive concurrently or services temporarily fail.

## 2.4 Prefer explicit domain boundaries

Use microservices, but keep boundaries clear and services relatively small.

## 2.5 Automation over manual repetition

Local development, tests, builds, containers and deployments should be automated as early as practical.

## 2.6 Infrastructure must be reproducible

Prefer Docker Compose locally and Terraform for cloud infrastructure where practical.

---

# 3. Scope

## 3.1 MVP Scope

### Customer

- Registration/sign-in through Entra External ID.
- Email/password authentication.
- Google sign-in.
- Event carousel/home page.
- Event listing.
- Event details.
- Seat-map view.
- Multiple seat selection (maximum 10 seats per reservation).
- Reservation confirmation.
- 15-minute temporary seat hold.
- Stripe payment flow using Stripe Test Mode.
- Payment success/failure handling via Stripe Payment Intents and webhooks.
- Ticket creation.
- QR code generation.
- Email confirmation.
- My reservations / My tickets.
- Real-time seat status updates with WebSocket.

### Admin

- Sign-in as administrator.
- Create event.
- Update event.
- Configure venue reference and seat map.
- Configure seat categories/prices.
- View reservation and sales information.

### Platform

- Microservices.
- REST APIs.
- Kafka asynchronous events.
- Outbox Pattern.
- Redis.
- PostgreSQL.
- Docker.
- Testcontainers.
- OpenTelemetry.
- Prometheus.
- Grafana.
- GitHub Actions CI/CD.
- Google Cloud deployment.
- Environment separation.
- Centralized secrets in production.

## 3.2 Post-MVP / Phase 2

- AI assistant.
- MCP/tool calling.
- Natural-language seat search/reservation.
- Advanced seat-map editor.
- Multiple sessions/showings per event.
- Multiple venues.
- Refunds.
- Ticket scanning/entry validation application.
- Additional identity providers.
- Advanced analytics.

## 3.3 Explicit Non-Goals

Do not implement during MVP:

- Complex CMS.
- Loyalty program.
- Coupons/discount engine beyond basic seat-category pricing.
- Reviews/comments.
- Social networking.
- Wishlist.
- User profiles with extensive personal data.
- Mobile applications.
- Large-scale admin dashboard.
- Custom authentication/password-reset system.
- Fully graphical drag-and-drop seat-map editor.
- Live payment processing with production financial credentials.

---

# 4. High-Level Architecture

SeatFlow uses a **microservice-based, event-driven architecture** with two communication styles:

1. **Synchronous communication:** REST/HTTP between the frontend, API Gateway and services when an immediate request/response is required. Service-to-service REST calls resolve instances through Eureka instead of hardcoded hostnames.
2. **Asynchronous communication:** Kafka for domain events, decoupled workflows, notifications, payment outcomes, ticket creation, reservation expiration and realtime propagation.

Eureka is a **registry**, not a runtime hop in the request path. The Gateway does not call "Eureka then service". It queries Eureka for service instances and routes directly to the selected instance.

```text
                                      ┌─────────────────────────────┐
                                      │        Angular SPA          │
                                      │ REST + WebSocket / OIDC     │
                                      └──────────────┬──────────────┘
                                                     │
                                                     │ HTTPS / WS
                                                     ▼
                                      ┌─────────────────────────────┐
                                      │         API Gateway         │
                                      │ Routing / CORS / Auth       │
                                      └───────┬───────────┬─────────┘
                                              │           │
                              synchronous REST│           │WebSocket
                                              │           ▼
                                              │   ┌──────────────────┐
                                              │   │ Realtime Service │
                                              │   └────────┬─────────┘
                                              │            │
                                              │            │ consumes events
                                              ▼            │
              ┌──────────────────────────────────────────────────────────────┐
              │                         Eureka Server                         │
              │              service registry / discovery                    │
              └───────────────────────┬──────────────────────────────────────┘
                                      │
                     service instances register / discover
                                      │
      ┌───────────────────────────────┼──────────────────────────────────────────┐
      │                               │                                          │
      ▼                               ▼                                          ▼
┌──────────────┐                ┌──────────────┐                           ┌──────────────┐
│ User Service │                │ EventService │                           │ Seat Map     │
│              │                │              │                           │ Service      │
└──────┬───────┘                └──────┬───────┘                           └──────┬───────┘
       │                               │                                          │
       │                               │ REST for synchronous queries             │
       │                               │                                          │
       ▼                               ▼                                          ▼
 PostgreSQL                       PostgreSQL                                PostgreSQL

      ┌───────────────────────────────┼──────────────────────────────────────────┐
      │                               │                                          │
      ▼                               ▼                                          ▼
┌───────────────┐              ┌───────────────┐                           ┌──────────────┐
│ Reservation   │              │ Payment       │                           │ Ticket       │
│ Service       │              │ Service       │                           │ Service      │
└───────┬───────┘              └───────┬───────┘                           └──────┬───────┘
        │                              │                                          │
        │                              │ Stripe API + webhook                    │
        ▼                              ▼                                          ▼
 PostgreSQL                        Stripe                                    PostgreSQL
        │
        │ Outbox / domain events
        └──────────────────────────────┐
                                       │
                                       ▼
                              ┌──────────────────┐
                              │      Kafka       │
                              │ async event bus  │
                              └────────┬─────────┘
                                       │
                 ┌─────────────────────┼───────────────────────┐
                 │                     │                       │
                 ▼                     ▼                       ▼
        Notification Service     Realtime Service       downstream consumers
                 │                     │
                 ▼                     └───────► WebSocket clients
          External Email API

Shared technical infrastructure:
- Redis for justified coordination/caching/rate limiting/temporary hold metadata.
- OpenTelemetry + Micrometer + Prometheus + Grafana.
- Centralized cloud logs and structured JSON application logs.
```

## 4.1 Communication Rules

### REST / synchronous

Use REST when the caller needs an immediate answer and the operation is naturally request/response, for example:

```text
Angular -> API Gateway -> Event Service: GET published events
Angular -> API Gateway -> Seat Map Service: GET seat layout
Angular -> API Gateway -> Reservation Service: create reservation request
Reservation Service -> Seat Map Service: validate referenced seat metadata (only when required)
Gateway -> User Service: resolve current application user (only when required)
```

### Kafka / asynchronous

Use Kafka for events and workflows that can proceed asynchronously:

```text
ReservationCreated
ReservationExpired
PaymentInitiated
PaymentCompleted
PaymentFailed
TicketCreated
NotificationRequested
SeatStatusChanged
```

The presence of Kafka between services is intentional, but **not every service-to-service interaction must become asynchronous**. Avoid turning simple reads into event-driven round trips.

## 4.2 Kafka as the asynchronous backbone

For the MVP, Kafka is the primary asynchronous communication mechanism between business services. Critical state changes should produce domain events through the Outbox Pattern. Consumers must be idempotent.

A simplified event flow is:

```text
Reservation Service
        |
        | ReservationCreated
        v
      Kafka
        |
        +----> Payment Service
        |
        +----> Realtime Service
        |
        +----> Analytics / future consumers

Payment Service
        |
        | PaymentCompleted / PaymentFailed
        v
      Kafka
        |
        +----> Ticket Service
        +----> Reservation Service
        +----> Realtime Service

Ticket Service
        |
        | TicketCreated
        v
      Kafka
        |
        +----> Notification Service
        +----> Realtime Service
```

## 4.3 Database ownership

Every business microservice owns its own data. Services must never query another service's database directly.

For local development, multiple PostgreSQL databases can run inside one PostgreSQL container/instance to reduce resource usage. This is an infrastructure optimization, not permission to share schemas or repositories across services.

# 5. Proposed Microservices

## 5.0 Service Discovery and Common Modules

## 5.0.1 Eureka Discovery

Use **Netflix Eureka Server** for service discovery. Every Spring Boot business service and the API Gateway must register as a Eureka client. The Gateway should resolve downstream service instances through Eureka rather than hardcoding service hostnames/ports.

Local development may run one Eureka Server container/application instance. Production should use the simplest reliable topology supported by the chosen deployment model; do not create an unnecessary multi-node Eureka cluster for the portfolio MVP.

Responsibilities:

- service registration.
- service discovery.
- health-aware instance information where supported.
- removing hardcoded service URLs from the Gateway and inter-service clients.

Eureka is for **discovery**, not for business data, configuration, distributed locking or event transport.

## 5.0.2 Common Modules

A small set of shared Maven modules in `backend/common/` contains cross-cutting contracts and auto-configurations. Services inherit these modules as dependencies and **must not recreate or duplicate** the classes they provide:

```text
backend/
├── common/
│   ├── common-domain/
│   ├── common-events/
│   ├── common-observability/
│   └── common-security/
```

### `common-domain`
Cross-cutting domain contracts and primitives:
- `ApiErrorResponse`: standard API error payload record (`timestamp`, `status`, `error`, `errorCode`, `message`, `path`, `correlationId`, `validationErrors`).
- `PagedResult<T>`: standardized pagination wrapper (`content`, `pageNumber`, `pageSize`, `totalElements`, `totalPages`, `isFirst`, `isLast`).
- `ValidationError`: field-level validation issue representation (`field`, `rejectedValue`, `message`).
- Base domain exceptions: `BusinessException` (base unchecked exception), `ConflictException` (409), `ResourceNotFoundException` (404), `ValidationException` (400).
- `ErrorCode` enum: standardized error codes (`ERR_CONFLICT`, `ERR_RESOURCE_NOT_FOUND`, `ERR_SEAT_ALREADY_RESERVED`, `ERR_MAX_SEATS_EXCEEDED`, etc.).

### `common-events`
Shared Kafka event contracts and constants:
- `EventEnvelope<T>`: standard event envelope (`eventId`, `eventType`, `occurredAt`, `correlationId`, `causationId`, `aggregateId`, `version`, `payload`).
- `DomainEvent`: base marker interface for event payloads.
- `EventTopics`: standard topic naming constants (e.g. `seatflow.reservation.created`, `seatflow.payment.completed`).
- `EventHeaders`: standard Kafka header constants (`X-Correlation-Id`, `X-Causation-Id`, `X-Event-Type`).

### `common-observability`
Centralized error handling and tracing auto-configuration:
- `GlobalExceptionHandler`: Spring `@RestControllerAdvice` automatically imported by all web services. Catches `BusinessException`, `MethodArgumentNotValidException`, and unhandled `Exception`, formatting them into `ApiErrorResponse`.
- `CorrelationContext` & `CorrelationIdFilter`: extracts or generates `correlationId`, populating SLF4J MDC and response headers.
- `ObservabilityAutoConfiguration`: Spring Boot auto-configuration wiring the exception handler and correlation filter.

### `common-security`
Centralized security utilities and JWT claim mapping:
- `CommonSecurityAutoConfiguration`: configures JWT role conversion and method security (`@EnableMethodSecurity`).
- `JwtRoleConverter`: maps Entra / OIDC roles and scopes to Spring Security `GrantedAuthority` (`ROLE_USER`, `ROLE_ADMIN`).
- `SecurityRoles`: standard role constants (`ROLE_USER`, `ROLE_ADMIN`).
- `UserContext`: thread-safe helper providing `getCurrentUserId()`, `getCurrentUserEmail()`, and `hasRole()`.

## 5.0.3 General Backend Microservice Structure

Every business microservice follows the same internal organization so that the codebase remains predictable across services.

```text
<service-name>/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/seatflow/<service>/
    │   │   ├── config/            # Spring beans, Kafka/Redis/client config, service security rules
    │   │   ├── web/
    │   │   │   ├── controller/    # HTTP adapters only — no business logic
    │   │   │   └── dto/
    │   │   │       ├── request/   # Java Records with @Schema + Bean Validation
    │   │   │       └── response/  # Java Records with @Schema (ApiErrorResponse comes from common-domain)
    │   │   ├── service/           # Interfaces
    │   │   │   └── impl/          # Implementations (@Service, @RequiredArgsConstructor, @Slf4j)
    │   │   ├── repository/        # Spring Data JPA repositories
    │   │   ├── model/
    │   │   │   ├── entity/        # JPA entities with Lombok explicit annotations (never @Data)
    │   │   │   ├── enums/         # Service-owned enums
    │   │   │   ├── common/        # Intra-service shared records/value objects
    │   │   │   └── valueobject/   # Domain value objects where meaningful
    │   │   ├── mapper/            # MapStruct mappers
    │   │   ├── messaging/
    │   │   │   ├── consumer/      # Kafka consumers (idempotent)
    │   │   │   ├── producer/      # Kafka producers / Outbox publisher
    │   │   │   └── event/         # Local event payload definitions (implementing DomainEvent)
    │   │   └── client/            # Feign/RestClient for service-to-service REST calls
    │   └── resources/
    │       ├── application.yaml          # Base config (never contains secrets)
    │       ├── application-dev.yaml      # Development overrides
    │       ├── application-test.yaml     # Test overrides (Testcontainers)
    │       └── db/migration/             # Flyway migrations: V1__description.sql
    └── test/
        └── java/com/seatflow/<service>/
            ├── service/
            ├── repository/
            ├── web/
            └── messaging/
```

### Responsibility of each package

- `config/`: Spring configuration, beans, Kafka/Redis/client configuration, service-specific security rules.
- `web/controller/`: HTTP adapters only. No business rules.
- `web/dto/`: external API contracts as **Java Records** with `@Schema` and Bean Validation. Do not expose JPA entities directly.
- `service/`: application use case interfaces.
- `service/impl/`: application use case implementations (`@Service`, `@RequiredArgsConstructor`, `@Slf4j`).
- `repository/`: Spring Data JPA repositories for the service-owned database.
- `model/entity/`: service-owned JPA entities with explicit Lombok annotations (never `@Data`).
- `model/enums/`: service-owned enums (`@Enumerated(EnumType.STRING)` on entity fields).
- `model/common/`: intra-service shared records or value objects used across multiple packages of the same service.
- `model/valueobject/`: service-owned domain value objects where meaningful.
- `mapper/`: MapStruct mappers (`@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)`).
- `messaging/`: Kafka producers, consumers, and local event payload definitions.
- `client/`: typed service-to-service REST clients (`RestClient` or `@FeignClient`) when synchronous calls are justified.

### Service-specific additions

A service may add another package only when it has a real responsibility, for example:

```text
scheduler/     -> reservation expiration jobs in Reservation Service
stripe/        -> Stripe adapter/webhook code in Payment Service
websocket/     -> WebSocket handlers/publishers in Realtime Service
```

### Database migrations

Each service owns its own Flyway migrations under its own `resources/db/migration/` directory (`V1__...sql`). A service must be able to initialize its own database without executing another service's migrations.

## 5.0.5 Backend Code Standards

These standards apply to every business microservice. Agents implementing tasks must follow them without deviation.

### Entities — Lombok (explicit, never `@Data`) & Schema Integrity

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
@Check(constraints = "seat_count >= 1 AND seat_count <= 10")
@DynamicUpdate // Generates targeted SQL UPDATEs for modified columns only, reducing lock contention
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class Reservation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
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
    private Long version; // optimistic concurrency control

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

- `@NoArgsConstructor(access = AccessLevel.PROTECTED)` — required by JPA, prevents accidental public no-arg construction.
- Never use `@Data` or `@EqualsAndHashCode` on JPA entities (breaks Hibernate dynamic proxies and changes hash codes upon persistence).
- Always implement explicit `equals(Object o)` using `Hibernate.getClass(this) != Hibernate.getClass(o)` with `getId()` comparison, paired with constant `hashCode()` returning `getClass().hashCode()`.
- Use `@ToString(onlyExplicitlyIncluded = true)` or exclude relations to avoid lazy-loading loops and N+1 logging overhead.
- Use `@DynamicUpdate` on mutable entities to minimize update lock contention.
- Map JSONB columns with `@JdbcTypeCode(SqlTypes.JSON)`.
- Always `@Enumerated(EnumType.STRING)`, never `ORDINAL`.
- Use `@Version` where concurrent modification is possible.
- Use `GenerationType.UUID` for primary keys.

### DTOs — Java Records with Swagger and Validation

All request and response DTOs must be **Java Records**, not classes.

```java
// Request record (Supports both Guest and Authenticated flows)
@Schema(description = "Request body for creating a reservation")
public record CreateReservationRequest(

    @Schema(description = "Event ID", example = "123e4567-e89b-12d3-a456-426614174000")
    @NotNull UUID eventId,

    @Schema(description = "Customer email (required for guest checkouts, optional if authenticated)", example = "customer@seatflow.com")
    @Email String customerEmail,

    @Schema(description = "Customer/Attendee full name", example = "Alex Smith")
    String customerName,

    @Schema(description = "Seat IDs to reserve. Maximum 10.")
    @NotEmpty @Size(max = 10) List<@NotNull UUID> seatIds,

    @Schema(description = "Idempotency key")
    @NotBlank String idempotencyKey

) {}

// Response record
@Schema(description = "Reservation confirmation")
public record ReservationResponse(
    UUID id,
    UUID eventId,
    String customerEmail,
    String customerName,
    ReservationStatus status,
    Instant expiresAt,
    BigDecimal totalAmount,
    List<SeatSummary> seats
) {}
```

### Controllers — Full Swagger Documentation

Every controller endpoint must have `@Operation`, `@ApiResponse` for all relevant HTTP status codes (using `ApiErrorResponse.class` from `common-domain` for error responses), and `@Tag` on the class.

```java
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Tag(name = "Reservations", description = "Seat reservation management")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @Operation(summary = "Create a seat reservation (Guest or Authenticated)")
    @ApiResponse(responseCode = "201", description = "Created",
        content = @Content(schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(responseCode = "409", description = "Seat not available",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ReservationResponse> createReservation(
            @Valid @RequestBody CreateReservationRequest request) {
        Optional<String> userId = UserContext.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reservationService.createReservation(request, userId.orElse(null)));
    }
}
```

### Service Layer — Interface + Implementation

```java
// Interface in service/
public interface ReservationService {
    ReservationResponse createReservation(CreateReservationRequest request, String userId);
}

// Implementation in service/impl/
@Service
@RequiredArgsConstructor
@Slf4j
public class ReservationServiceImpl implements ReservationService {
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    @Override
    @Transactional
    public ReservationResponse createReservation(CreateReservationRequest request, String userId) {
        log.info("Creating reservation: userId={}, eventId={}, seatCount={}", userId, request.eventId(), request.seatIds().size());
        // business logic here
    }
}
```

- `@Transactional` on methods, not on the class.
- Log meaningful business events at `INFO` level. Include relevant IDs.
- Never log tokens, passwords, or card data.

### Mappers — MapStruct

```java
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReservationMapper {
    ReservationResponse toResponse(Reservation entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
    Reservation toEntity(CreateReservationRequest request);
}
```

- `unmappedTargetPolicy = ReportingPolicy.ERROR` — compilation fails if any target field is unmapped, preventing silent data loss.
- MapStruct 1.6.x supports Records natively.

### Error Handling & ApiErrorResponse (Centralized in `common-observability`)

Global exception handling is centralized in `common-observability` via `GlobalExceptionHandler` (`@RestControllerAdvice`). Services do not define duplicate exception handlers.

When a domain rule fails, services throw `BusinessException`, `ConflictException`, `ResourceNotFoundException`, or `ValidationException` from `com.seatflow.common.domain.exception`. All errors are automatically formatted into the standard `ApiErrorResponse`:

```java
// com.seatflow.common.domain.dto.ApiErrorResponse
public record ApiErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String errorCode,
    String message,
    String path,
    String correlationId,
    List<ValidationError> validationErrors
) {}
```

### Configuration Files

Use `application.yaml` (not `.yml`). Profiles: `dev`, `test`, `prod`.

- Base `application.yaml` contains structure with `${ENV_VAR}` placeholders — no secrets.
- `application-dev.yaml` contains developer-friendly local defaults.
- `application-test.yaml` configures Testcontainers for integration tests.
- `ddl-auto: validate` in all profiles — Flyway owns the schema, not Hibernate.

### Dependency Injection

- Always use constructor injection via `@RequiredArgsConstructor`.
- Never use `@Autowired` on fields.

### Swagger / OpenAPI

Use **springdoc-openapi 3.x** (`springdoc-openapi-starter-webmvc-ui`). Every controller must be discoverable and fully documented at `/swagger-ui.html`.



## 5.0.4 Service Communication Matrix

| Caller | Target | Protocol | Purpose |
|---|---|---|---|
| Angular | API Gateway | REST | API requests |
| Angular | Realtime Service | WebSocket | Live seat/status updates |
| API Gateway | User/Event/SeatMap/Reservation/Payment/Ticket/Admin APIs | REST via Eureka | Synchronous request/response |
| Reservation Service | Seat Map Service | REST via Eureka | Validate referenced seat metadata when required |
| Reservation Service | Kafka | Outbox -> event | Reservation lifecycle events |
| Payment Service | Stripe | HTTPS/webhooks | Payment processing |
| Payment Service | Kafka | Outbox/event | Payment outcome events |
| Ticket Service | Kafka | Consumer/producer | Ticket creation from payment success and ticket events |
| Notification Service | Email Provider | HTTPS/API | Ticket/confirmation delivery |
| Realtime Service | Kafka | Consumer | Receive state changes for WebSocket fan-out |

Rules:

- REST is for immediate reads/commands where a synchronous response is required.
- Kafka is for domain events and asynchronous workflows.
- No service reads another service's database.
- Eureka resolves service instances for synchronous service-to-service REST.
- Kafka does not replace Eureka.
- Eureka does not replace Kafka.

# 5.1 API Gateway

### Responsibility

- External entry point.
- Routing.
- Authentication token propagation.
- CORS policy.
- Correlation/trace propagation.
- Basic rate limiting where appropriate.

### Must not

- Contain business logic.
- Query business databases directly.

---

## 5.2 Identity/User Service

### Responsibility

- Maintain SeatFlow user/business record.
- Map Entra `sub`/subject to internal user ID.
- Maintain application role (`USER`, `ADMIN`).
- Expose minimal profile/account data.

### Important

The identity provider owns authentication credentials. SeatFlow stores only required business identity references and account metadata.

---

## 5.3 Event Service

### Responsibility

- Events.
- Event descriptions.
- Dates/times.
- Venue association.
- Seat-map association.
- Event publishing/unpublishing.
- Event listing for the Angular frontend.

### Example states

```text
DRAFT -> PUBLISHED -> SOLD_OUT -> FINISHED
          |
          +-> CANCELLED
```

Keep the state machine explicit.

---

## 5.4 Venue / Seat Map Service

### Responsibility

- Venue definitions.
- Seat map templates.
- Sections.
- Rows.
- Seat positions.
- Seat categories.
- Pricing metadata.

### MVP simplification

Only one venue/seat-map style is required initially. The data model must allow future expansion to multiple venues and multiple seat layouts.

### Seat map representation

Use normalized seat metadata plus layout coordinates/ordering. Do not build a complex free-form graphics editor for MVP.

Suggested seat fields:

```text
seatId
seatMapId
sectionId
rowLabel
seatNumber
categoryId
positionX
positionY
```

Position coordinates exist so Angular can render a visually convincing hall layout and so later features can rank seats by distance from stage.

---

# 6. Seat Categories and Pricing

Seats support different categories inspired by theatre/event layouts, for example:

- Central / Premium
- Normal
- Balcony
- Box / Loja

The exact labels are configurable.

Do not hardcode student/pensioner as physical seat types.

Instead, model:

```text
SeatCategory
    -> base price

PriceRule / PriceClass
    -> Regular
    -> Student
    -> Pensioner
    -> other future customer classes
```

This allows the same seat to have a different price based on customer eligibility.

For MVP, a minimal rule set is sufficient:

```text
REGULAR
STUDENT
```

The future AI seat-selection use case will rely on these pricing/eligibility concepts.

---

# 7. Reservation Service

This is the most important business service from a distributed-systems perspective.

## 7.1 Reservation states

## 7.0 Reservation Size Limit

A single reservation request may contain **at most 10 seats/tickets**. Requests containing more than 10 seats must be rejected server-side with a validation error such as `RESERVATION_LIMIT_EXCEEDED`. This prevents users from holding an unreasonable portion of a venue for the 15-minute reservation window. The limit applies regardless of whether the request comes from the Angular UI or an API/AI tool.

```text
PENDING / HELD
   |
   +---- payment success ----> CONFIRMED
   |
   +---- timeout ------------> EXPIRED
   |
   +---- user cancellation -> CANCELLED
```

## 7.2 Seat states

```text
AVAILABLE
   |
   v
HELD
   |
   +---- payment success ----> SOLD
   |
   +---- timeout ------------> AVAILABLE
```

## 7.3 Reservation timeout

A successful seat confirmation request creates a 15-minute hold.

Example:

```text
heldAt      = now
expiresAt   = now + 15 minutes
```

The reservation must not remain active after expiration.

## 7.4 Concurrency requirement

If N users attempt to reserve the same seat at the same time, exactly one successful reservation may claim it.

This must be validated by automated concurrency integration tests.

Candidate implementation techniques:

- PostgreSQL transactional guarantees.
- Optimistic locking/versioning.
- Unique constraints on active seat reservations.
- Redis coordination only where justified.

Do not rely on frontend state to guarantee seat exclusivity.

---

# 8. Payment Service

## 8.1 Payment provider

Use **Stripe** as the payment provider integration for the production-oriented project. Stripe is selected because it provides a well-known developer ecosystem, Java SDK/support, test mode, Payment Intents, webhooks and a realistic industry payment workflow without requiring us to process raw card data ourselves.

For local development and automated tests, use **Stripe test mode** and test payment methods. Never use real card data and never persist card numbers/CVV in SeatFlow.

## 8.2 Payment architecture

The Angular application should use the Stripe-supported client-side payment flow, while Spring Boot creates and confirms the server-side payment intent/order context. Keep payment-provider details behind a `PaymentProvider` abstraction so tests can use a deterministic fake implementation without requiring Stripe network calls.

Conceptual flow:

```text
Angular
   |
   | checkout/payment method
   v
Payment Service
   |
   | create PaymentIntent
   v
Stripe Test/Production
   |
   | webhook
   v
Payment Service
   |
   v
PaymentCompleted / PaymentFailed
```

## 8.3 Webhooks

The backend must treat Stripe webhooks as the authoritative asynchronous confirmation mechanism for payment status where applicable. Webhook handlers must be idempotent and verify Stripe signatures. Do not trust a frontend-only "payment successful" callback.

## 8.4 States

```text
INITIATED
  |
  +----> REQUIRES_ACTION
  |
  +----> SUCCESS
  |
  +----> FAILED
  |
  +----> EXPIRED / CANCELLED
```

The provider should be abstracted so integration tests can deterministically simulate success, failure and timeout without contacting Stripe.

# 9. Ticket Service

After successful payment:

1. Create ticket record(s).
2. Generate a unique ticket identifier.
3. Generate QR payload.
4. Persist ticket state.
5. Publish a ticket-created event.

## Ticket states

```text
ACTIVE
USED
CANCELLED
```

The QR must not contain sensitive personal or payment data.

### QR Code Generation — ZXing

Use **ZXing (Zebra Crossing)** for QR code generation. Add `com.google.zxing:javase` to the Ticket Service `pom.xml`.

```java
// QR payload: opaque ticket token (UUID or signed JWT without sensitive claims)
// QR image: generated as byte[] PNG and embedded in the email or returned as base64 to the frontend
QRCodeWriter writer = new QRCodeWriter();
BitMatrix bitMatrix = writer.encode(ticketToken, BarcodeFormat.QR_CODE, 300, 300);
ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
byte[] qrImage = outputStream.toByteArray();
```

The QR payload must contain only an opaque ticket reference (UUID or a signed, non-sensitive JWT). Future ticket scanning validates status server-side by looking up that reference.


---

# 10. Notification Service

### Responsibility

- Email confirmations.
- Ticket delivery.
- Optional future notification types.

Use **JavaMailSender** (Spring Boot `spring-boot-starter-mail`) via SMTP as the email delivery mechanism. The concrete SMTP server (Gmail, Cloudflare Email, or any SMTP-compatible provider) is configured through environment variables and never hardcoded.

The provider must be hidden behind an `EmailProvider` interface so that integration tests can use a no-op or in-memory fake without sending real emails.

```java
// Interface in notification-service
public interface EmailProvider {
    void send(EmailMessage message);
}

// Production implementation using JavaMailSender
@Component
@RequiredArgsConstructor
public class SmtpEmailProvider implements EmailProvider {
    private final JavaMailSender mailSender;

    @Override
    public void send(EmailMessage message) {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        // build and send
        mailSender.send(mimeMessage);
    }
}
```

SMTP configuration via environment variables:

```yaml
# application.yaml
spring:
  mail:
    host: ${MAIL_HOST}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

Email should be triggered asynchronously from payment/ticket events.

Example:

```text
PaymentCompleted
        |
        v
TicketService creates tickets
        |
        v
TicketCreated
        |
        v
NotificationService
        |
        v
EmailProvider (SMTP) → Email with QR code
```


---

# 11. Realtime Service

This service is responsible for WebSocket-facing functionality.

## Client connection

Angular establishes a WebSocket connection after authentication.

## Important event

```text
SEAT_STATUS_CHANGED
```

Example payload:

```json
{
  "eventId": "...",
  "seatId": "...",
  "status": "HELD",
  "expiresAt": "2026-08-22T15:30:00Z"
}
```

The frontend must update the visible seat map without refreshing the page.

## WebSocket Protocol — Spring STOMP + SockJS

Use **Spring WebSocket with STOMP** (Simple Text Oriented Messaging Protocol) over **SockJS** as the fallback transport.

**Backend:** `spring-boot-starter-websocket` with `@EnableWebSocketMessageBroker`. Configure a STOMP endpoint at `/ws` with SockJS fallback and an in-memory message broker.

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("${seatflow.cors.allowed-origins}")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }
}
```

Kafka consumer in Realtime Service converts incoming `SeatStatusChanged` events into STOMP broadcasts:

```java
// Publish to all subscribers of the event's seat topic
messagingTemplate.convertAndSend("/topic/events/" + eventId + "/seats", seatStatusUpdate);
```

**Frontend:** Use **`@stomp/stompjs`** with SockJS fallback (`sockjs-client`). Subscribe to `/topic/events/{eventId}/seats` to receive live seat updates.

## Distributed Deployment Concern

When multiple application instances are running, WebSocket clients may be connected to different instances. Therefore, WebSocket state must not exist only in local process memory.

Use the Kafka consumer in Realtime Service to receive `SeatStatusChanged` events from all service instances and fan out to locally connected WebSocket clients. This ensures seat updates produced by any instance reach all connected browsers.


---

# 12. Kafka Event Model

Kafka is used for **backend-to-backend asynchronous communication** and event-driven workflows.

Do not use Kafka as a substitute for every REST call.

## Kafka Client

Use **spring-kafka** (`spring-boot-starter`) as the Kafka client library. It is the standard Spring integration and is managed by the Spring Boot BOM.

Kafka topics are consumed and produced using `@KafkaListener` and `KafkaTemplate<String, Object>` with JSON serialization via Jackson.

## Suggested Events

```text
EventPublished
ReservationCreated
ReservationConfirmed
ReservationExpired
ReservationCancelled
PaymentInitiated
PaymentCompleted
PaymentFailed
TicketCreated
SeatStatusChanged
NotificationRequested
```

## Topic Naming Convention

```text
seatflow.reservation.created
seatflow.reservation.expired
seatflow.payment.completed
seatflow.payment.failed
seatflow.ticket.created
seatflow.seat.status-changed
```

## Event Envelope (required for all events)

Every event must use this envelope, defined in `common-events`. Every field is mandatory.

```json
{
  "eventId": "uuid",            // unique ID for deduplication on consumer side
  "eventType": "ReservationCreated",
  "occurredAt": "2026-08-23T10:00:00Z",
  "correlationId": "uuid",      // ties this event to an originating HTTP request or flow
  "causationId": "uuid",        // ID of the event that directly caused this event (null for origin events)
  "aggregateId": "uuid",        // e.g. reservationId, paymentId, ticketId
  "version": 1,                 // event schema version — increment on breaking changes
  "payload": {}
}
```

Event contracts must be versioned intentionally. Do not change the payload shape of an existing version; increment `version` instead.


---

# 13. Outbox Pattern

The Reservation, Payment, Ticket and other services must avoid an unsafe dual-write such as:

```text
DB commit
then
Kafka publish
```

Instead:

```text
BEGIN TRANSACTION
    update business state
    insert into outbox_events (aggregate_id, event_type, payload)
COMMIT

Outbox publisher (@Scheduled)
    -> reads unpublished rows
    -> publishes to Kafka
    -> marks as published
```

### Outbox Table Schema

Each event-producing service owns its own outbox table:

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

### Publisher Implementation

The outbox publisher is a Spring `@Scheduled` component that runs every 5 seconds, reads unpublished events (up to 100 at a time), publishes them to Kafka, and marks them as published. This is the MVP implementation. Debezium CDC may replace it in a production-hardened deployment.

```java
@Scheduled(fixedDelay = 5000)
@Transactional
public void publishPendingEvents() {
    outboxEventRepository.findUnpublishedEvents(Pageable.ofSize(100))
        .forEach(event -> {
            try {
                kafkaTemplate.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload());
                event.setPublishedAt(Instant.now());
            } catch (Exception ex) {
                log.error("Outbox publish failed: eventId={}", event.getId(), ex);
                event.setRetryCount(event.getRetryCount() + 1);
            }
            outboxEventRepository.save(event);
        });
}
```

If Kafka is unavailable, the outbox event remains persisted and will be retried on the next scheduler cycle. After 5 failed retries (`retry_count = 5`), the event is no longer retried automatically and requires manual intervention or a dead-letter process.

The outbox publisher is idempotent: it reads only rows where `published_at IS NULL` and sets `published_at` atomically.


---

# 14. Redis

Redis is NOT the source of truth for ticket ownership.

PostgreSQL remains authoritative.

Redis can be used for:

- temporary reservation metadata/holds.
- caching event/seat-map reads where beneficial.
- rate limiting.
- distributed coordination where needed.
- WebSocket fan-out support if required.

Every Redis usage must answer the question: “What problem does Redis solve here that PostgreSQL alone would not solve as effectively?”

---

# 15. Authentication and Authorization

## 15.1 Identity provider

Use **Microsoft Entra External ID** for customer identity.

Primary login options:

1. Email + password.
2. Google account sign-in.

The authentication system is delegated to Entra. SeatFlow does not manage raw user passwords.

## 15.2 Angular

Use the Microsoft-supported MSAL Angular/OIDC approach.

Use Authorization Code + PKCE for the SPA.

## 15.3 Backend

Spring Security acts as OAuth2 Resource Server and validates JWT access tokens.

Required checks:

- signature
- issuer
- audience
- expiration
- scopes/claims as appropriate
- application role mapping

## 15.4 Application roles

```text
USER
ADMIN
```

The backend owns authorization decisions for business operations.

Example:

```text
GET /api/events              -> USER, ADMIN
POST /api/admin/events       -> ADMIN
GET /api/me/tickets          -> USER, ADMIN
```

Do not rely only on hiding admin buttons in Angular.

---

# 16. Account Model

The customer account is intentionally minimal.

Required business fields:

```text
User
----
id
externalSubject
email
role
createdAt
```

Do not collect unnecessary personal data.

The account page should show:

- email.
- purchased/upcoming tickets.
- past tickets.
- ticket details.

No complex profile-management feature is required.

---

# 17. Frontend UX

Angular is intentionally lightweight.

## Main pages

### 17.1 Home

- Hero / short introduction.
- Upcoming events carousel.
- Featured event cards.
- Login state.

### 17.2 Events

- Event grid/list.
- Basic filtering only if needed.

### 17.3 Event Details

- Event title.
- Date and time.
- Venue.
- Description.
- Pricing summary.
- “Choose seats” CTA.

### 17.4 Seat Selection

Core visual feature.

```text
                STAGE

     [A1] [A2] [A3] [A4] [A5]

 [B1] [B2] [B3] [B4] [B5] [B6]

      [C1] [C2] [C3] [C4]
```

Colors/status examples:

```text
Available       -> selectable
Held by me      -> selected/held
Held by another -> unavailable
Sold            -> unavailable
```

The actual visual design can be inspired by real theatre seat layouts, but the MVP does not require a pixel-perfect clone of another website.

### Layout spacing requirement

The MVP must support meaningful visual spacing between rows, columns and sections. Do not render the seat map as a dense uniform grid. Layout coordinates and/or row/column spacing values should allow configurations such as:

```text
                STAGE

 [A1] [A2] [A3]          [A4] [A5]

 [B1] [B2] [B3] [B4] [B5] [B6]

        [C1] [C2]      [C3] [C4]
```

Use `positionX` / `positionY`, row/column offsets, section gaps or equivalent normalized layout data. The goal is to make the seat map visually resemble a real theatre, while keeping the editor implementation intentionally simple.

### 17.5 Reservation / Checkout

- Selected seats.
- Price breakdown.
- 15-minute countdown.
- Stripe payment element / test payment flow.
- Confirm payment.

### 17.6 My Tickets

- Upcoming tickets.
- Past tickets.
- Event details.
- Seat information.
- QR code.

### 17.7 Admin

Minimal admin UI:

- Event creation.
- Event editing.
- Price/category configuration.
- Basic reservations view.

---


## 17.8 Recommended Angular Frontend Structure

**Angular 22** (current stable) with **TypeScript 6** and **Node 22+**. Use **standalone components** exclusively — no NgModule. Avoid introducing NgRx for MVP; Angular Signals are sufficient.

### Styling

- **Angular Material 22** — use for complex components: `MatTable`, `MatDialog`, `MatSnackBar`, `MatFormField`, `MatDatepicker`, navigation components.
- **TailwindCSS v4** — use for layout, spacing, flexbox/grid, custom colors, and responsive design.
- Coexistence strategy: Angular Material handles component-level theming in `styles.scss`; TailwindCSS handles structural layout and spacing utility classes.
- Do not apply Tailwind utility classes directly to Angular Material component markup — this can cause visual conflicts.

### Recommended Structure

```text
frontend/seatflow-web/
├── src/
│   ├── app/
│   │   ├── core/
│   │   │   ├── auth/
│   │   │   ├── http/
│   │   │   ├── guards/
│   │   │   ├── interceptors/
│   │   │   ├── services/
│   │   │   └── websocket/
│   │   ├── layout/
│   │   │   ├── shell/
│   │   │   └── admin-shell/
│   │   ├── shared/
│   │   │   ├── components/
│   │   │   ├── ui/
│   │   │   ├── pipes/
│   │   │   ├── directives/
│   │   │   └── models/
│   │   ├── features/
│   │   │   ├── home/
│   │   │   ├── events/
│   │   │   ├── event-details/
│   │   │   ├── seat-selection/
│   │   │   ├── checkout/
│   │   │   ├── tickets/
│   │   │   └── admin/
│   │   ├── app.routes.ts
│   │   └── app.config.ts
│   ├── assets/
│   ├── environments/
│   └── styles/
└── angular.json
```

### Frontend Responsibilities

- `core/`: singleton application concerns — authentication (MSAL), HTTP interceptors, API clients, route guards, WebSocket service.
- `layout/`: application shells and navigation containers.
- `shared/`: reusable presentational components with no feature-specific business logic.
- `features/`: user/admin functionality organized by business feature.
- `models/`: frontend API models and view models. Do not mirror backend entities blindly.

### Component Pattern

```typescript
@Component({
  selector: 'app-seat-map',
  standalone: true,
  imports: [MatButtonModule, MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,  // required on all components
  templateUrl: './seat-map.component.html',
})
export class SeatMapComponent {
  private readonly seatService = inject(SeatService);

  readonly seats = this.seatService.seats;           // Signal<Seat[]>
  readonly selectedSeats = signal<Set<string>>(new Set());
}
```

- `ChangeDetectionStrategy.OnPush` — required on all components (Angular 22 default).
- Use `inject()` function for dependency injection in standalone components.
- Use Angular Signals (`signal()`, `computed()`, `effect()`) for reactive state — do not use `BehaviorSubject` in component state management.

### Frontend State Strategy

- **Signals** for local and cross-component reactive state.
- **Feature services** (`@Injectable({ providedIn: 'root' })`) for API state and frontend orchestration.
- **WebSocket service** using `@stomp/stompjs` with `sockjs-client` fallback that converts incoming events into Signals.
- Route resolvers/loaders only where they clearly improve UX.

```typescript
// WebSocket with @stomp/stompjs
this.client = new Client({
  webSocketFactory: () => new SockJS('/ws'),
  connectHeaders: { Authorization: `Bearer ${token}` },
  onConnect: () => {
    this.client.subscribe(`/topic/events/${eventId}/seats`, (message) => {
      const update = JSON.parse(message.body);
      this.seatUpdates.set(update);  // Signal update
    });
  },
});
```

The seat map must keep server state as the source of truth. Optimistic UI changes are allowed for responsiveness, but a server response or WebSocket event always corrects the local view.


# 18. Seat Map Architecture

The seat-map data model must support future expansion without building a complex editor now.

Suggested hierarchy:

```text
Venue
  |
  +-- SeatMap
        |
        +-- Section
              |
              +-- Row
                    |
                    +-- Seat
```

Seat should have:

```text
id
seatMapId
sectionId
rowLabel
seatNumber
categoryId
positionX
positionY
status/calculated availability
```

## MVP layout strategy

Use a predefined layout template created in backend/data fixtures.

Optional admin editing should be limited to:

- number of rows.
- number of seats per row.
- category.
- price class.
- visibility/enabled state.

A drag-and-drop graphical editor is explicitly post-MVP.

---

# 19. Reservation Flow

## 19.1 Standard user flow

```text
Browse events
   |
   v
Open event
   |
   v
Load seat map
   |
   v
Select 1..N seats
   |
   v
Confirm seats
   |
   v
Create 15-min hold
   |
   +---- conflict ----> display updated seat state
   |
   v
Checkout
   |
   v
Payment provider
   |
   +---- failure ------> release/retain according to explicit policy
   |
   v
PaymentCompleted
   |
   v
Create tickets
   |
   v
Send email
   |
   v
Display QR tickets
```

## 19.2 Expiration flow

```text
HELD
  |
  | 15 minutes elapsed
  v
EXPIRED
  |
  v
Seat becomes AVAILABLE
  |
  v
Publish SeatStatusChanged
  |
  v
WebSocket clients update UI
```

---

# 20. Reservation Concurrency Strategy

This is a core interview/demo feature.

## Required guarantees

1. A seat cannot be held by two active reservations at once.
2. A seat cannot become SOLD twice.
3. A retry of the same client request must not create duplicate reservations.
4. Expiration must be idempotent.
5. Payment completion must be idempotent.

## Recommended mechanisms

- Database unique constraints.
- Transaction boundaries.
- Optimistic locking/version columns where useful.
- Idempotency keys on create-reservation/payment operations.
- Redis only as a supporting coordination layer.

The database remains authoritative for correctness.

---

# 21. Idempotency

Client operations that can be retried must support idempotency.

Candidates:

```text
POST /reservations
POST /payments
```

Example:

```http
Idempotency-Key: 4b3a...
```

The same key sent twice must return the same logical operation result rather than create a duplicate.

Implement an explicit policy for retention/expiration of idempotency records.

---

# 22. Email and QR

After successful payment:

```text
PaymentCompleted
      |
      v
TicketService
      |
      v
TicketCreated
      |
      v
NotificationService
```

Email should contain:

- event title.
- venue.
- date/time.
- seat identifiers.
- ticket identifier(s).
- QR code.

Do not put confidential information in the QR payload.

Local development uses the same `EmailProvider` abstraction. Configure `spring.mail.*` in `application-dev.yaml` to point at your SMTP server (Gmail, Cloudflare, or a local SMTP relay). Test profiles use a no-op `EmailProvider` implementation that records sent messages without dispatching them.


---

# 23. REST API Principles

Use resource-oriented APIs.

Examples:

```text
GET    /api/events
GET    /api/events/{eventId}
GET    /api/events/{eventId}/seat-map

POST   /api/reservations
GET    /api/reservations/{reservationId}
POST   /api/reservations/{reservationId}/confirm
POST   /api/reservations/{reservationId}/cancel

POST   /api/payments
GET    /api/me/tickets
GET    /api/tickets/{ticketId}

POST   /api/admin/events
PUT    /api/admin/events/{eventId}
GET    /api/admin/reservations
```

Use **springdoc-openapi 3.x** (`springdoc-openapi-starter-webmvc-ui`) for API documentation. The Swagger UI must be available at `/swagger-ui.html` in `dev` and `staging` profiles. Disable it in `prod` if not needed publicly.

Every controller must use `@Tag`, `@Operation`, and `@ApiResponse` for all documented HTTP status codes. Every DTO record must use `@Schema` on the record and on individual fields where description or example adds clarity.

Do not expose internal service/database implementation details in public API contracts.

---

# 24. Error Handling

Error handling is centralized across all microservices using **`common-observability`** (`GlobalExceptionHandler`) and **`common-domain`** (`ApiErrorResponse`, `BusinessException`, `ErrorCode`).

All API errors return the standardized `ApiErrorResponse` structure:

```json
{
  "timestamp": "2026-08-23T10:00:00Z",
  "status": 409,
  "error": "Conflict",
  "errorCode": "ERR_SEAT_ALREADY_RESERVED",
  "message": "One or more selected seats are no longer available.",
  "path": "/api/reservations",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "validationErrors": null
}
```

Standard domain error codes (defined in `com.seatflow.common.domain.exception.ErrorCode`):

```text
ERR_INTERNAL_SERVER_ERROR
ERR_RESOURCE_NOT_FOUND
ERR_VALIDATION_FAILED
ERR_CONFLICT
ERR_UNAUTHORIZED
ERR_FORBIDDEN
ERR_IDEMPOTENCY_CONFLICT
ERR_SEAT_ALREADY_RESERVED
ERR_RESERVATION_EXPIRED
ERR_MAX_SEATS_EXCEEDED
ERR_PAYMENT_FAILED
```

When a business validation fails, services throw an appropriate exception from `common-domain` (e.g. `ConflictException`, `ResourceNotFoundException`, `ValidationException`), and `GlobalExceptionHandler` automatically maps it to `ApiErrorResponse` with the appropriate HTTP status and error code.


---

# 25. Observability

Observability must be centralized and based on standard telemetry rather than only custom AOP messages such as `method started` / `method finished`. AOP-based logging may still be used for a small number of business audit points, but it is **not** the primary observability strategy.

## 25.1 Centralized structured logging

All microservices must emit structured JSON logs to `stdout/stderr`. In cloud, platform logging collects those streams centrally. Locally, Docker Compose should make service logs accessible in one place; optionally provide a lightweight log collector/viewer if it adds clear value.

Every log entry should include, when available:

- timestamp
- serviceName
- environment
- log level
- traceId
- spanId
- correlationId
- operation
- HTTP method/path for HTTP requests
- aggregateId/reservationId/ticketId when relevant
- error type/message for failures

Use **SLF4J + Logback** (or the current Spring Boot default logging stack) with JSON structured output. Prefer OpenTelemetry trace/span context in MDC so logs and traces can be correlated.

Never log:

- passwords
- access tokens
- Stripe secrets
- raw card data
- sensitive authentication payloads

## 25.2 Distributed tracing

Use **OpenTelemetry** as the standard instrumentation layer. Propagate W3C trace context across:

```text
Angular -> API Gateway -> service -> database
                           |
                           +-> Kafka -> consumer service
```

For asynchronous Kafka work, preserve correlation/trace context in message headers so the resulting consumer span can be connected to the originating request/event.

Important business flows must be traceable end-to-end:

```text
POST /reservations
  -> reservation DB transaction
  -> outbox insert
  -> Kafka publish
  -> payment processing
  -> ticket creation
  -> notification
```

## 25.3 Metrics

Use **Micrometer** for application metrics and expose Prometheus-compatible metrics.

Minimum metrics:

```text
http.server.requests
reservation.created.total
reservation.conflict.total
reservation.expired.total
payment.success.total
payment.failed.total
kafka.consumer.lag
websocket.connections.active
notification.sent.total
notification.failed.total
```

Also capture JVM/process metrics supplied by the standard Spring/Micrometer integration.

## 25.4 Dashboards and alerts

Use Grafana for a small set of dashboards:

1. HTTP/API health.
2. Reservation/payment flow.
3. Kafka and asynchronous processing.
4. WebSocket/realtime behavior.

Alerting is optional for MVP, but at minimum the dashboards should make failures and latency visible.

## 25.5 Centralized error diagnosis

Every API error should return a `traceId`/correlation identifier that can be searched in logs and matched to an OpenTelemetry trace. This is preferred over manual logging annotations because one trace can explain the entire distributed operation.

## 25.6 AOP policy

AOP may be used for very targeted cross-cutting concerns, but do not create an annotation that simply logs `method started` and `method finished` for every service method. Such logging creates noise and does not provide distributed context.

If an AOP helper is retained, limit it to explicit business/audit events where the semantics are meaningful. Technical observability comes from OpenTelemetry, Micrometer, structured logs, trace correlation and centralized log/metric backends.

# 26. Testing Strategy

## 26.1 Unit Tests

Use **JUnit 5** (`junit-jupiter`) and **Mockito** (`mockito-core`). Both are managed by the Spring Boot BOM.

Focus on domain and business rules. Do not write unit tests for getters, setters, or generated boilerplate. Test the logic that can fail or behave differently under different inputs.

```java
@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {
    @InjectMocks ReservationServiceImpl service;
    @Mock ReservationRepository repository;

    @Test
    void shouldRejectWhenMoreThan10SeatsRequested() { ... }
}
```

## 26.2 Integration Tests

Use **Testcontainers** for real infrastructure dependencies (managed by the Testcontainers BOM in the parent pom):

- `PostgreSQLContainer` for PostgreSQL.
- `KafkaContainer` (Confluent image) for Kafka.
- `GenericContainer` for Redis where relevant.

Use `@DynamicPropertySource` to inject Testcontainers connection strings into the Spring context. Activate the `test` profile (`@ActiveProfiles("test")`).

## 26.3 Concurrency Tests

Required for any code path that modifies reservation or seat state.

```java
@Test
void onlyOneWinnerWhenConcurrentReservationsSameSeat() throws InterruptedException {
    UUID seatId = createAvailableSeat();
    int threads = 100;
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger failureCount = new AtomicInteger();

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
        executor.submit(() -> {
            try {
                latch.await();
                reservationService.createReservation(requestFor(seatId));
                successCount.incrementAndGet();
            } catch (SeatNotAvailableException e) {
                failureCount.incrementAndGet();
            }
        });
    }

    latch.countDown();
    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS);

    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failureCount.get()).isEqualTo(threads - 1);
}
```

The test must assert correctness (`successCount == 1`), not merely the absence of exceptions.

## 26.4 Contract/API Tests

Verify REST/OpenAPI contracts using `@WebMvcTest` with MockMvc, or Spring Boot's `RestAssured` integration for full slice tests. The response shape must match the `ApiErrorResponse` and DTO Records defined in the spec.

## 26.5 End-to-End Tests

Use **Playwright** for a small number of critical user journeys:

1. Sign in.
2. Browse event.
3. Choose seats.
4. Complete payment (Stripe test mode).
5. View ticket and QR code.

Do not create hundreds of brittle UI tests. Five meaningful end-to-end scenarios are more valuable than fifty shallow ones.


---

# 27. Local Development Environment

Target developer experience:

```text
clone repository
   |
   v
docker compose up
   |
   v
run backend/frontend
   |
   v
application is usable locally
```

## Local infrastructure

```text
PostgreSQL
Redis
Kafka
Prometheus
Grafana
(optional local OpenTelemetry Collector)
```

Use sensible local defaults.

No cloud dependency should be required to develop core business features.

Authentication is an intentional external dependency and may require configured Entra local redirect URIs.

---

# 28. Environment Strategy

Use three conceptual environments:

```text
DEV
STAGING
PROD
```

## DEV

- local Docker.
- developer-friendly configuration.
- development/test email provider credentials or sandbox mode.
- development Entra app registration/redirect URI.

## STAGING

- cloud deployment.
- realistic infrastructure configuration.
- safe test data.
- deployment validation.

## PROD

- production configuration.
- real HTTPS.
- production secrets.
- production identity configuration.
- monitored application.

Never reuse production secrets in local development.

---

# 29. Git Workflow

Preferred strategy:

```text
feature/*
    |
    v
 Pull Request
    |
    v
 develop
    |
    v
 staging deployment
    |
    v
 Pull Request develop -> main
    |
    v
   main
    |
    v
 production deployment
```

## Branch protection

`main` must not accept direct pushes.

A merge into `main` must require successful CI.

Production deployment should be associated with a known commit/tag/revision.

---

# 30. CI/CD

## Pull Request CI

```text
Checkout
  -> setup Java
  -> build
  -> unit tests
  -> integration tests
  -> static analysis
  -> frontend build
  -> frontend tests
```

## Production deployment

Triggered after merge to `main`.

```text
Checkout
  -> build backend
  -> build frontend
  -> run tests
  -> build Docker images
  -> push images to Artifact Registry
  -> deploy backend to Cloud Run
  -> deploy frontend
  -> run smoke tests
```

Use GitHub Actions.

Prefer Google Cloud Workload Identity Federation over long-lived service account JSON keys.

---

# 31. Google Cloud Target Architecture

Production target:

```text
Internet
   |
   +--> Angular static hosting
   |
   +--> Cloud Run API Gateway
              |
              +--> Cloud Run Event Service
              +--> Cloud Run Reservation Service
              +--> Cloud Run Payment Service
              +--> Cloud Run Ticket Service
              +--> Cloud Run Notification Service
              +--> Cloud Run Realtime Service

Shared cloud services:
   - Cloud SQL PostgreSQL
   - Secret Manager
   - Artifact Registry
   - IAM
   - monitoring/logging
   - messaging layer as selected
```

The exact production messaging architecture may evolve depending on cost and operational simplicity.

Cloud infrastructure must not be hardcoded into application logic.

---

# 32. Terraform

After the application architecture stabilizes, provision cloud infrastructure using Terraform.

Candidate resources:

```text
Cloud Run services
Artifact Registry
Cloud SQL
Secret Manager
IAM service accounts
Workload Identity Federation
networking
monitoring resources where appropriate
```

Terraform must be modular enough to distinguish environments.

Do not commit cloud secrets to Terraform source files.

---

# 33. CORS and Production Configuration

Do not use wildcard CORS in production.

Use environment-driven values.

Example conceptual configuration:

```text
DEV:
  FRONTEND_URL=http://localhost:4200

STAGING:
  FRONTEND_URL=https://staging.seatflow.example

PROD:
  FRONTEND_URL=https://seatflow.example
```

Backend must allow only known origins in each environment.

Also configure:

- allowed headers.
- allowed methods.
- credentials behavior if cookies are ever used.
- WebSocket origin checks where applicable.

---

# 34. Security Requirements

## Mandatory

- OIDC authentication.
- JWT validation.
- Role-based authorization.
- Input validation.
- Server-side authorization.
- Secrets outside source control.
- Secure CORS configuration.
- HTTPS in cloud.
- No card storage.
- No passwords in application DB.
- No sensitive data in logs.
- Idempotency for financial/reservation operations.

## Future hardening

- rate limiting per identity/IP.
- abuse detection.
- security headers.
- dependency scanning.
- container image scanning.
- stronger audit trails.

---

# 35. AI / MCP Phase 2

AI is explicitly optional after the MVP is production-ready.

## Goal

Provide a SeatFlow assistant capable of reasoning over event and seat availability and using controlled backend tools.

Example:

> “Reserve a student seat for Hamlet as close to the stage as possible.”

## Proposed architecture

```text
Angular Chat
    |
    v
AI Service
    |
    v
Spring AI
    |
    +---- MCP / tool calling
             |
             +--> searchEvents
             +--> getEvent
             +--> getAvailableSeats
             +--> findBestSeat
             +--> createReservation
             +--> getReservation
```

## Tool contracts

### searchEvents

Input:

```text
query
optional date range
```

Returns matching events.

### getAvailableSeats

Input:

```text
eventId
optional category
```

Returns eligible seats and pricing.

### findBestSeat

Input:

```text
eventId
customerClass = STUDENT
preference = CLOSEST_TO_STAGE
optional maxPrice
```

Returns ranked seats with reasons.

### createReservation

Creates a temporary hold only after explicit user confirmation.

The AI must not silently purchase or confirm payment.

### Design rule

The AI must interact through domain APIs/tools. It must never get direct unrestricted database credentials.

---

# 36. Seat Ranking for AI

The backend should own the seat-ranking algorithm.

Possible ranking inputs:

```text
distance to stage
seat category
price
visibility score
availability
user constraints
```

Example:

```text
score =
    0.50 * stageProximity
  + 0.25 * visibility
  + 0.15 * categoryPreference
  + 0.10 * pricePreference
```

This is illustrative. The exact formula should be domain-driven and documented.

The AI should select from backend-computed eligible candidates rather than inventing seat IDs.

---

# 37. API and Event Documentation

Every public REST endpoint must have OpenAPI documentation.

Every Kafka event must have:

- event name.
- purpose.
- producer.
- consumers.
- schema.
- versioning policy.
- idempotency considerations.
- failure/retry behavior.

---

# 38. Required ADRs

Create ADR documents for at least:

1. Why microservices are used for SeatFlow.
2. Why PostgreSQL is the source of truth for seat state.
3. Why Redis is not the authoritative store.
4. Why Kafka is used for asynchronous backend communication.
5. Why Outbox Pattern is required.
6. Why WebSocket is used for live seat availability.
7. Why OIDC/Entra External ID is used instead of custom auth.
8. Why the seat-map editor is intentionally limited in MVP.
9. Why the `PaymentProvider` abstraction and Stripe Test Mode are used.
10. How WebSocket state works in a multi-instance cloud deployment.
11. Why production is deployed to GCP/Cloud Run.
12. Why AI/MCP are Phase 2 rather than MVP.

---

# 39. Project Phases

## Phase 0 — Architecture

Deliverables:

- architecture diagram.
- domain model.
- service boundaries.
- REST contracts.
- event contracts.
- ADRs.
- environment strategy.

No major coding yet.

## Phase 1 — Foundation

- monorepo structure.
- backend service skeletons.
- Eureka Server + Eureka client registration.
- Angular skeleton.
- Docker Compose.
- CI.
- Entra integration.
- health checks.
- base observability.

## Phase 2 — Event + Venue

- event management.
- venue.
- seat map.
- seat categories.
- prices.
- event carousel.

## Phase 3 — Reservation Core

- select seats.
- hold for 15 minutes.
- concurrency control.
- expiration.
- idempotency.
- conflict handling.

## Phase 4 — Async Architecture

- Kafka.
- Outbox.
- ReservationCreated/Expired/Confirmed.
- Payment events.
- Notification events.

## Phase 5 — Payment + Tickets

- Stripe Test Mode via `PaymentProvider` abstraction.
- ticket generation.
- QR.
- email.

## Phase 6 — Real-time UX

- WebSocket.
- live seat state.
- reservation countdown.
- multi-client synchronization.

## Phase 7 — Production Engineering

- Testcontainers.
- OpenTelemetry.
- Prometheus.
- Grafana.
- resilience.
- security hardening.
- CI/CD.

## Phase 8 — Cloud

- GCP.
- Cloud Run.
- Cloud SQL.
- Artifact Registry.
- Secret Manager.
- IAM/WIF.
- Terraform.
- staging/prod deployment.

## Phase 9 — Optional AI

- Spring AI.
- tool calling/MCP.
- smart seat search.
- natural language reservation.

---

# 40. Implementation Playbook

This section defines the recommended implementation order. It is deliberately sequential: the project should gain one architectural capability at a time, and each phase should leave the repository in a runnable state.

## 40.1 Phase 0 — Repository and Architecture Foundations

Deliver:

- root repository and Git strategy.
- root Maven configuration for the backend multi-module build.
- Angular workspace.
- `backend/` and `frontend/` structure.
- `common-*` modules.
- architecture/ADR directories.
- initial README.
- GitHub Actions skeleton for pull-request CI.

Do not implement business functionality yet.

## 40.2 Phase 1 — Infrastructure Skeleton

Implement in this order:

1. PostgreSQL container(s).
2. Redis.
3. Kafka + Kafka UI/health tooling only if useful locally.
4. Eureka Server.
5. API Gateway.
6. basic health endpoints and service registration.
7. common observability setup.

Acceptance:

```text
Gateway -> Eureka discovery -> a sample service
Gateway -> service through discovered route
All services register successfully
Trace/log context appears in requests
```

## 40.3 Phase 2 — Identity and User Service

Implement:

1. Microsoft Entra External ID integration.
2. Angular MSAL/OIDC flow.
3. Spring Security Resource Server JWT validation.
4. User Service persistence of `externalSubject`, email and role.
5. `USER` / `ADMIN` authorization.
6. current-user endpoint.

Frontend work in this phase:

- login/callback flow.
- protected routes.
- application shell.
- auth-aware navigation.

## 40.4 Phase 3 — Seat Map Service First

Build the static seat-map domain before the reservation engine.

Implement:

- Venue.
- SeatMap.
- Section.
- Row.
- Seat.
- SeatCategory.
- PriceClass/PriceRule.
- positionX/positionY and spacing metadata.
- Flyway migrations.
- seed data representing one theatre layout inspired by the supplied reference image.

Frontend:

- reusable seat component.
- legend.
- layout container.
- rendering using normalized coordinates and configurable gaps.

Do not build a drag-and-drop editor.

## 40.5 Phase 4 — Event Service

Implement:

- event CRUD.
- publish/unpublish state.
- event -> venue/seat map reference.
- event listing/detail REST APIs.
- admin event management.

Frontend:

- home carousel.
- event cards.
- event details page.
- basic loading/error/empty states.

## 40.6 Phase 5 — Reservation Service and Concurrency

This is the most important business phase.

Implement first:

1. reservation model.
2. maximum 10-seat validation.
3. 15-minute hold semantics.
4. seat availability checks.
5. database constraints/locking strategy.
6. idempotency key support.
7. reservation expiration scheduler.
8. concurrency integration tests.

Required test:

```text
100 concurrent attempts
       |
       v
same seat
       |
       v
exactly one active winner
```

Frontend:

- seat selection page.
- selection summary.
- max-10 validation UX.
- 15-minute countdown.
- conflict/retry UI.

Do not proceed to payment until reservation correctness is proven.

## 40.7 Phase 6 — Kafka and Outbox

Add asynchronous communication after the synchronous core is correct.

Implement:

- event envelope.
- Kafka topics.
- outbox table per event-producing service where needed.
- outbox publisher.
- idempotent consumers.
- correlation/causation metadata.

Start with a small set of events:

```text
ReservationCreated
ReservationExpired
SeatStatusChanged
```

Then add payment/ticket events.

## 40.8 Phase 7 — Stripe Payment Service

Implement Stripe Test Mode, not live financial processing.

Recommended sequence:

1. `PaymentProvider` abstraction.
2. fake provider for tests.
3. Stripe adapter.
4. PaymentIntent creation.
5. frontend Stripe payment integration.
6. signed webhook endpoint.
7. webhook idempotency.
8. payment state machine.
9. Reservation -> Payment -> final state event flow.

Important rule: the frontend must never be the authoritative source of payment success.

## 40.9 Phase 8 — Ticket Service and Email Notifications

Implement:

- ticket persistence.
- opaque ticket identifier.
- QR generation.
- ticket status.
- TicketCreated event.
- Notification Service consumer.
- external email provider abstraction.
- provider sandbox/test mode.

Frontend:

- payment confirmation.
- My Tickets.
- ticket details.
- QR display.

## 40.10 Phase 9 — Realtime WebSocket

Implement only after seat state transitions are correct.

Sequence:

1. SeatStatusChanged domain event.
2. Kafka consumer in Realtime Service.
3. WebSocket endpoint/STOMP or an equally simple Spring-supported WebSocket protocol.
4. authenticated client connection.
5. fan-out to connected clients.
6. reconnect handling.
7. server-authoritative reconciliation after reconnect.

Demo requirement:

```text
Browser A reserves seat A12
        |
        v
Kafka event
        |
        v
Realtime Service
        |
        v
Browser B sees A12 unavailable without refresh
```

## 40.11 Phase 10 — Angular Completion

After backend business flows stabilize, complete the frontend end-to-end:

- Home.
- Events.
- Event details.
- Seat selection.
- Checkout.
- Ticket confirmation.
- My Tickets.
- minimal Admin.

Use reusable components and feature-based organization. Do not spend time on pixel-perfect cloning or pages that do not demonstrate the core flow.

## 40.12 Phase 11 — Observability and Reliability

Implement centrally:

- structured JSON logging.
- OpenTelemetry tracing.
- trace propagation through Kafka.
- Micrometer metrics.
- Prometheus.
- Grafana dashboards.
- centralized cloud logging.
- error correlation through traceId.
- retry/timeout policies for external calls.

Add failure demos and runbooks before calling the system production-oriented.

## 40.13 Phase 12 — Testing and Quality Gates

Add and enforce:

- unit tests.
- integration tests with Testcontainers.
- concurrency tests.
- API/contract tests.
- limited Playwright E2E tests.
- static analysis.
- dependency/image scanning where practical.

PRs must fail when critical tests fail.

## 40.14 Phase 13 — Staging and Production

Build environments in this order:

1. local Docker Compose.
2. staging cloud environment.
3. production cloud environment.

Then add:

- Terraform.
- Secret Manager.
- GitHub Workload Identity Federation.
- Artifact Registry.
- Cloud Run.
- Cloud SQL.
- production CORS.
- production OIDC redirect URIs.
- smoke tests after deployment.
- rollback procedure.

## 40.15 Phase 14 — Optional AI / MCP

Only after the full MVP is stable:

1. create AI Service.
2. add Spring AI.
3. expose read-only domain tools.
4. implement `findBestSeat` ranking.
5. add explicit reservation confirmation.
6. only then consider `createReservation` through an MCP/tool-calling interface.

AI must not become a shortcut around domain authorization or the 10-seat/15-minute reservation rules.

## 40.16 Phase 15 — Portfolio Polish

Only after correctness and deployment are complete:

- README screenshots/GIFs.
- architecture diagram.
- failure demonstrations.
- concise demo video.
- ADR cleanup.
- API/event documentation.
- performance test summary.
- final CV bullet points.

Avoid polishing low-value pages before the core architecture is finished.

## 40.17 Parallelization Rules

Some work can be parallelized after contracts are stable:

```text
Backend domain contract ──┬──> Angular feature
                          ├──> integration tests
                          └──> API documentation
```

But these should not be implemented independently before their contract exists.

A good development pattern is:

```text
define contract
    |
    +---- backend implementation
    |
    +---- frontend implementation
    |
    +---- tests
    |
    +---- observability
    v
integrate
```

# 41. Definition of Done — MVP

The MVP is complete only when all of the following are true:

- [ ] User can sign up/sign in with email/password.
- [ ] User can sign in with Google.
- [ ] User can browse published events.
- [ ] User can open event details.
- [ ] User can view the seat map.
- [ ] User can select multiple seats.
- [ ] Server validates seat availability.
- [ ] Concurrent reservations cannot double-book a seat.
- [ ] Seats are held for exactly 15 minutes according to business rules.
- [ ] Expired holds release seats.
- [ ] Payment flow is simulated.
- [ ] Payment state is idempotent.
- [ ] Successful payments create tickets.
- [ ] Tickets contain QR codes.
- [ ] Confirmation email is generated.
- [ ] User can view purchased tickets.
- [ ] Seat changes are pushed to clients through WebSocket.
- [ ] Backend events are published through Kafka where defined.
- [ ] Outbox Pattern is implemented for critical domain events.
- [ ] Redis is used only for justified concerns.
- [ ] Unit tests exist for domain logic.
- [ ] Integration tests use Testcontainers.
- [ ] Concurrency tests verify single-winner reservation behavior.
- [ ] OpenAPI is available.
- [ ] OpenTelemetry is configured.
- [ ] Prometheus metrics are available.
- [ ] Grafana dashboard is available.
- [ ] Local environment starts through Docker Compose.
- [ ] GitHub Actions CI runs automatically.
- [ ] Production deployment is automated.
- [ ] Production secrets are not stored in Git.
- [ ] CORS is environment-specific.
- [ ] README explains setup and architecture.
- [ ] ADRs explain major architecture decisions.

---

# 42. Definition of Done — Production Deployment

- [ ] Angular production build deployed.
- [ ] Backend services deployed to GCP.
- [ ] Database deployed/configured for production.
- [ ] Secrets stored in Secret Manager.
- [ ] Workload Identity Federation configured for GitHub Actions.
- [ ] HTTPS enabled.
- [ ] Correct production CORS configured.
- [ ] OIDC redirect URIs configured for production.
- [ ] Logs visible in cloud logging.
- [ ] Application metrics visible.
- [ ] Health checks configured.
- [ ] Smoke tests run after deployment.
- [ ] Deployment can be rolled back.
- [ ] Staging and production configuration are separated.

---

# 43. Suggested Repository Structure

The repository is split into backend microservices, a feature-oriented Angular frontend, infrastructure-as-code and documentation. `AGENTS.md` contains repository-level development instructions; this document remains the architecture/product contract.

```text
seatflow/
├── AGENTS.md
├── backend/
│   ├── pom.xml
│   ├── api-gateway/
│   │   └── src/main/java/com/seatflow/gateway/
│   │       ├── config/
│   │       ├── filter/
│   │       ├── route/
│   │       └── security/
│   │
│   ├── eureka-server/
│   │   └── src/main/java/com/seatflow/eureka/
│   │       └── config/
│   │
│   ├── user-service/
│   ├── event-service/
│   ├── seatmap-service/
│   ├── reservation-service/
│   ├── payment-service/
│   ├── ticket-service/
│   ├── notification-service/
│   ├── realtime-service/
│   │
│   └── common/
│       ├── common-domain/
│       ├── common-events/
│       ├── common-observability/
│       └── common-security/
│
├── frontend/
│   └── seatflow-web/
│       └── src/app/
│           ├── core/
│           ├── layout/
│           ├── shared/
│           └── features/
│
├── infrastructure/
│   ├── docker/
│   ├── terraform/
│   └── gcp/
│
├── docs/
│   ├── architecture/
│   ├── adr/
│   ├── api/
│   ├── events/
│   ├── deployment/
│   └── runbooks/
│
├── .github/
│   └── workflows/
│
├── docker-compose.yml
└── README.md
```

The exact Java package prefix and build tooling may be refined during Phase 0. The logical boundaries above are authoritative.

---

# 44. Performance / Reliability Targets for Demo Purposes

These are engineering targets, not enterprise SLAs.

- Seat selection endpoint should normally respond within a few hundred ms locally under normal load.
- WebSocket seat updates should appear near-real-time.
- Reservation expiration should recover seats promptly after the configured TTL.
- Integration tests should be repeatable.
- A repeated client request with the same idempotency key should not create duplicates.
- A single seat should have one active owner at a time.

Do not optimize prematurely. Measure first.

---

# 45. Failure Scenarios to Demonstrate

The project should intentionally support demos of:

1. Two users attempting the same seat simultaneously.
2. Payment failure.
3. Payment timeout.
4. Duplicate payment callback/event.
5. Reservation expiration.
6. Kafka temporarily unavailable.
7. Notification provider temporarily unavailable.
8. Client disconnected/reconnected to WebSocket.
9. Backend instance restart.
10. Rollback to a previous Cloud Run revision.

These scenarios are more valuable than dozens of additional UI features.

---

# 46. Demo Scenarios for the Portfolio

The README/demo should showcase at least:

### Demo A — Normal Purchase

```text
Login -> Event -> Seats -> Hold -> Payment -> Ticket -> QR -> Email
```

### Demo B — Concurrency

```text
Two clients -> same seat -> exactly one wins
```

### Demo C — Real-Time

```text
Client A reserves seat -> Client B UI updates instantly
```

### Demo D — Expiration

```text
Hold created -> countdown -> timeout -> seat returns to AVAILABLE
```

### Demo E — Failure Handling

```text
Payment fails -> reservation remains/reverts according to policy -> event published -> UI updated
```

### Demo F — Optional AI

```text
Natural language request -> tool calls -> recommended seat -> explicit user confirmation -> reservation
```

---

# 47. Final Architectural Philosophy

SeatFlow should demonstrate these capabilities in increasing order of maturity:

```text
Java / Spring Boot
        |
        v
REST + SQL
        |
        v
Microservices
        |
        v
Kafka / Event Driven
        |
        v
Concurrency + Idempotency
        |
        v
Outbox + Reliability
        |
        v
WebSocket / Real-Time
        |
        v
Observability
        |
        v
Testing / CI/CD
        |
        v
Cloud / IaC
        |
        v
Optional AI / MCP
```

The project succeeds if it remains understandable while demonstrating depth.

The goal is not to maximize the number of technologies. The goal is to create a system where each technology exists because a concrete engineering problem requires it.

---

# 48. Current Reference Links

The following links are included only as implementation references and should be rechecked before final deployment because cloud pricing and product capabilities can change.

- Microsoft Entra External ID pricing: https://learn.microsoft.com/en-us/entra/external-id/external-identities-pricing
- Microsoft Entra External ID Google federation: https://learn.microsoft.com/en-us/entra/external-id/customers/how-to-google-federation-customers
- Microsoft Entra External ID Angular SPA sample: https://learn.microsoft.com/en-us/samples/azure-samples/ms-identity-ciam-javascript-tutorial/ms-identity-ciam-javascript-tutorial-2-sign-in-angular/
- Spring Security OAuth2 Login: https://docs.spring.io/spring-security/reference/servlet/oauth2/login/
- Spring Boot OAuth2 Resource Server: https://docs.spring.io/spring-boot/reference/security/oauth2.html
- Google Cloud Run: https://cloud.google.com/run
- Google Cloud Run pricing: https://cloud.google.com/run/pricing
- Cloud Run WebSockets: https://cloud.google.com/run/docs/triggering/websockets
- Google Cloud Secret Manager pricing: https://cloud.google.com/secret-manager/pricing
- Google Cloud free program: https://cloud.google.com/free

---
