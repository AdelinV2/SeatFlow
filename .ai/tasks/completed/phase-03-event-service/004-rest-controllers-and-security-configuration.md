# TASK-P03-004: Event REST Controllers, Seat Map Client & Security

## 1. Task Metadata
- **Task ID:** `TASK-P03-004`
- **Git Branch:** `feat/p03-004-rest-controllers-and-security-configuration`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 03 - Event Catalog Service`
- **Related Specs:** `.ai/architecture/04-authentication-security.md`, `.ai/architecture/06-api-contracts.md` (Section 2.3), `.ai/architecture/02-microservices-spec.md` (Sections 4–5)
- **Related ADRs:** `None`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Expose the public catalog and secured administration APIs, connect event validation/layout composition to `seat-map-service`, and enforce server-side authorization. The event service remains the authority for event status and price tiers; the seat-map service remains the authority for venue layout.

### Critical Invariants to Enforce:
- [ ] `GET /api/events/**`, Swagger UI, API docs, and `/actuator/health` are public; every `/api/admin/**` route requires `ROLE_ADMIN` server-side.
- [ ] Controllers return only records/common envelopes, never JPA entities, exception advice, or business logic.
- [ ] Public reads include only published upcoming events; a non-published id returns the common 404 envelope.
- [ ] Create/update/pricing requests use `@Valid`, return 201/200 respectively, and reject invalid bodies with the shared 400 envelope.
- [ ] Seat-map composition only overlays prices for matching active sections; never invent a seat layout or price for an unknown section.
- [ ] Seat-map 404 becomes an event validation/not-found response as appropriate; timeout/5xx/circuit-open failures are explicit 503 service-unavailable responses, not empty layouts.
- [ ] The client propagates `X-Correlation-Id` and uses a Resilience4j circuit breaker.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/config/RestClientConfig.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/config/SecurityConfig.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/client/SeatMapClient.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/client/impl/SeatMapClientImpl.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/client/SeatMapClientUnavailableException.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/controller/EventController.java`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/web/controller/AdminEventController.java`
- `[MODIFY]` `backend/services/event-service/src/main/resources/application.yaml` — add `seat-map-service` URL/timeouts and Resilience4j instance properties.
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/web/controller/EventControllerTest.java`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/web/controller/AdminEventControllerTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Seat Map Client
Create a `RestClient` bean named `seatMapRestClient` with base URL `${seat-map-service.base-url:http://localhost:8082}`, connect/read timeout of two seconds, and a request interceptor that forwards `CorrelationContext.getCorrelationId()` as `X-Correlation-Id` when present. Configure `resilience4j.circuitbreaker.instances.seatMapClient` with a count-based window of 10, failure-rate threshold 50, open-state wait duration 30 seconds, and permitted half-open calls 3.

`SeatMapClient` is an interface in `com.seatflow.event.client` extending Task 003 `VenueValidationPort`:

```java
public interface SeatMapClient extends VenueValidationPort {
    SeatMapVenueLayout getVenueLayout(UUID venueId);
}

public record SeatMapVenueLayout(
    UUID venueId, String name, Integer capacity, Long totalConfiguredSeats, List<SeatMapVenueSection> sections
) {}

public record SeatMapVenueSection(
    UUID sectionId, String name, Integer rowCount, Integer colCount, List<SeatMapVenueSeat> seats
) {}

public record SeatMapVenueSeat(
    UUID seatId, String rowLabel, Integer seatNumber, Integer gridX, Integer gridY, Boolean isActive
) {}
```

`SeatMapClientImpl` in `com.seatflow.event.client.impl` is a `@Service` implementing `SeatMapClient`. It implements `venueExists` by `GET /api/venues/{venueId}` and `getVenueLayout` by `GET /api/venues/{venueId}/layout`. `sectionBelongsToVenue` loads the layout and checks section ids. Map upstream 404 to `false` for the two boolean methods; for `getVenueLayout`, throw `ResourceNotFoundException` including the venue id. Map connection, timeout, 5xx, and circuit-breaker failures to `SeatMapClientUnavailableException`, a `BusinessException` with HTTP 503 and `ErrorCode.INTERNAL_SERVER_ERROR`; no fallback may return fabricated venue data.

### 4.2 Security Configuration
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(JwtRoleConverter jwtRoleConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }
}
```

The implementation must import `AbstractHttpConfigurer`, `SessionCreationPolicy`, `HttpMethod`, `JwtAuthenticationConverter`, `HttpSecurity`, `SecurityFilterChain`, and the shared `JwtRoleConverter`; it must never create a service-local role converter. `SecurityRoles.ADMIN` is used in `@PreAuthorize("hasRole('ADMIN')")` or the equivalent `SecurityFilterChain` rule.

### 4.3 Public Controller OpenAPI Contract
`EventController` is `@RestController`, `@RequestMapping("/api/events")`, `@Tag(name = "Events (Public)", description = "Public published event catalog and priced seat maps")`, and has these exact methods:

```java
@GetMapping
@Operation(summary = "List published upcoming events", description = "Returns a paginated public catalog filtered by optional category and case-insensitive search.")
@ApiResponse(responseCode = "200", description = "Events retrieved", content = @Content(schema = @Schema(implementation = PagedResult.class)))
ResponseEntity<PagedResult<EventSummaryResponse>> listEvents(
    @RequestParam(required = false) EventCategory category,
    @RequestParam(required = false) @Size(max = 100) String search,
    @PageableDefault(size = 20, sort = "eventDate", direction = Sort.Direction.ASC) Pageable pageable);

@GetMapping("/{eventId}")
@Operation(summary = "Get published event details", description = "Returns published event metadata and its active pricing tiers.")
@ApiResponse(responseCode = "200", description = "Event retrieved", content = @Content(schema = @Schema(implementation = EventDetailResponse.class)))
@ApiResponse(responseCode = "404", description = "Event not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
ResponseEntity<EventDetailResponse> getEvent(@PathVariable UUID eventId);

@GetMapping("/{eventId}/seat-map")
@Operation(summary = "Get priced event seat map", description = "Combines the authoritative venue layout with this event's active section pricing tiers.")
@ApiResponse(responseCode = "200", description = "Priced seat map retrieved", content = @Content(schema = @Schema(implementation = EventSeatMapResponse.class)))
@ApiResponse(responseCode = "404", description = "Event or venue not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
@ApiResponse(responseCode = "503", description = "Seat map service unavailable", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
ResponseEntity<EventSeatMapResponse> getSeatMap(@PathVariable UUID eventId);
```

Before calling the catalog service, validate that `page >= 0`, `size` is 1–100, and sort is only `eventDate`, `title`, or `createdAt`; invalid pagination/sort throws `ValidationException(ErrorCode.INVALID_REQUEST)`. `getSeatMap` is a pure HTTP adapter that delegates directly to `eventService.getEventSeatMap(eventId)`.

### 4.4 Admin Controller OpenAPI Contract
`AdminEventController` is `@RestController`, `@RequestMapping("/api/admin/events")`, `@Tag(name = "Events (Administration)", description = "Administrative event lifecycle and pricing APIs")`, and all methods have `@PreAuthorize("hasRole('ADMIN')")`:

```java
@PostMapping
@Operation(summary = "Create a draft event", description = "Creates a DRAFT event after validating the referenced venue.")
@ApiResponse(responseCode = "201", description = "Draft event created", content = @Content(schema = @Schema(implementation = EventDetailResponse.class)))
@ApiResponse(responseCode = "400", description = "Invalid request or venue", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
ResponseEntity<EventDetailResponse> createEvent(@Valid @RequestBody CreateEventRequest request);

@GetMapping("/{eventId}")
@Operation(summary = "Get event details for administration", description = "Returns full event metadata including draft/cancelled/completed status.")
@ApiResponse(responseCode = "200", description = "Event retrieved", content = @Content(schema = @Schema(implementation = EventDetailResponse.class)))
@ApiResponse(responseCode = "404", description = "Event not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
ResponseEntity<EventDetailResponse> getEvent(@PathVariable UUID eventId);

@PutMapping("/{eventId}")
@Operation(summary = "Update an event or transition its status", description = "Applies a partial metadata update and validates lifecycle transitions.")
@ApiResponse(responseCode = "200", description = "Event updated", content = @Content(schema = @Schema(implementation = EventDetailResponse.class)))
@ApiResponse(responseCode = "400", description = "Invalid update or state transition", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
@ApiResponse(responseCode = "404", description = "Event not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
ResponseEntity<EventDetailResponse> updateEvent(@PathVariable UUID eventId, @Valid @RequestBody UpdateEventRequest request);

@PostMapping("/{eventId}/pricing")
@Operation(summary = "Replace event section pricing", description = "Validates section ownership and atomically replaces pricing tiers for an editable event.")
@ApiResponse(responseCode = "200", description = "Pricing configured", content = @Content(schema = @Schema(implementation = PricingTierResponse.class)))
@ApiResponse(responseCode = "400", description = "Invalid pricing or immutable event", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
ResponseEntity<List<PricingTierResponse>> configurePricing(@PathVariable UUID eventId, @Valid @RequestBody ConfigurePricingRequest request);
```

Creation must use `ResponseEntity.status(HttpStatus.CREATED)`. No controller may catch `BusinessException`, create `@RestControllerAdvice`, inspect claims directly, or duplicate application-layer validation.

### 4.5 Web Slice Test Contracts
Use `@WebMvcTest`, `@Import(SecurityConfig.class)`, `@MockitoBean` for services/client/JWT decoder/converter, and `@WithMockUser` for role cases. `EventControllerTest` proves unauthenticated public list/detail/seat-map success, catalog 404, client 503, bad pagination/search validation 400, and that seat map endpoint returns `EventSeatMapResponse`. `AdminEventControllerTest` proves admin 201 create, admin 200 get, 200 update and pricing, invalid body 400, customer 403, unauthenticated 401, state validation 400, and service 404 propagation. Verify OpenAPI endpoint routing through MockMvc; do not start a database or actual HTTP client in these slice tests.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p03-004-rest-controllers-and-security-configuration` from `develop`.
2. Add the configured RestClient and circuit breaker adapter implementing the existing venue port.
3. Add stateless OAuth2 resource-server security using the shared JWT converter.
4. Implement public controllers, strict pageable allow-listing, and the seat map pricing overlay.
5. Implement admin controllers with validated bodies, documented responses, and `ROLE_ADMIN` authorization.
6. Add MockMvc slice coverage for routing, validation, error envelopes, public access, and RBAC.
7. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/event-service -Dtest=EventControllerTest,AdminEventControllerTest
```

- [ ] All public and admin endpoint contracts have OpenAPI annotations and exact status codes.
- [ ] Authorization is enforced in both filter rules and controller tests.
- [ ] Seat-map failure never creates fictional layout data.
- [ ] No JPA entity or local exception advice reaches the web layer.
- [ ] Task file is moved to `.ai/tasks/completed/phase-03-event-service/004-rest-controllers-and-security-configuration.md` when complete.
