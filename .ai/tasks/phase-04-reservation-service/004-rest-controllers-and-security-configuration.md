# TASK-P04-004: Reservation REST Controllers, Security Configuration & OpenAPI Documentation

## 1. Task Metadata
- **Task ID:** `TASK-P04-004`
- **Git Branch:** `feat/p04-004-rest-controllers-and-security-configuration`
- **Target Module:** `backend/services/reservation-service`
- **Phase:** `Phase 04 - Reservation & Hold Service`
- **Related Specs:** `.ai/architecture/04-authentication-security.md`, `.ai/architecture/06-api-contracts.md` (Section 2.4), `.ai/architecture/02-microservices-spec.md` (Section 6)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Expose the HTTP REST endpoints for seat reservation holds, cancellations, and real-time seat availability. Configure stateless Spring Security 7 OAuth2 Resource Server integration using the shared `JwtRoleConverter`, and document all endpoints with Swagger/OpenAPI annotations.

### Critical Invariants to Enforce:
- [ ] **Hybrid Guest Checkout (ADR-001):** `POST /api/reservations` is publicly accessible. If an `Authorization: Bearer <JWT>` token is present, resolve `userId` and token claims via `UserContext`; otherwise, process the hold for an unauthenticated guest using the provided `customerEmail`.
- [ ] **Server-Side Authorization:** `SecurityConfig` permits public access to `/api/reservations`, `/api/reservations/*`, `/api/reservations/*/cancel`, `/api/reservations/events/*/availability`, `/v3/api-docs/**`, `/swagger-ui/**`, and `/actuator/health`. Secured operations extract roles using the auto-configured `JwtRoleConverter` from `common-security`.
- [ ] **Pure HTTP Adapters:** Controllers contain zero business logic or entity mappings. All input validation uses Jakarta `@Valid`, and methods return `ResponseEntity<T>` with explicit HTTP status codes (`201 CREATED`, `200 OK`, `204 NO_CONTENT`).
- [ ] **Centralized Exception Handling:** Controllers do not catch exceptions or define `@RestControllerAdvice`. All exceptions bubble to `GlobalExceptionHandler` from `common-observability`.
- [ ] **OpenAPI / Swagger Documentation:** Every endpoint is annotated with `@Operation`, `@ApiResponse` schemas (including `ApiErrorResponse` on 400, 404, 409, 503), and OpenAPI tags.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/config/RestClientConfig.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/config/SecurityConfig.java`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/web/controller/ReservationController.java`
- `[MODIFY]` `backend/services/reservation-service/src/main/resources/application.yaml` — configure `event-service` client base URL, timeouts, and Resilience4j circuit breaker instance properties with `ignoreExceptions`.
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/web/controller/ReservationControllerTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 RestClient Configuration
`RestClientConfig.java` in `com.seatflow.reservation.config`:

```java
package com.seatflow.reservation.config;

import com.seatflow.common.observability.context.CorrelationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Value("${event-service.base-url:http://localhost:8083}")
    private String eventServiceBaseUrl;

    @Bean
    public RestClient eventRestClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        return builder
                .baseUrl(eventServiceBaseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    CorrelationContext.getCorrelationId().ifPresent(corrId ->
                            request.getHeaders().set("X-Correlation-Id", corrId));
                    return execution.execute(request, body);
                })
                .build();
    }
}
```

### 4.2 Security Configuration
`SecurityConfig.java` in `com.seatflow.reservation.config`:

```java
package com.seatflow.reservation.config;

import com.seatflow.common.security.converter.JwtRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

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
                // Public reservation creation & availability check (Hybrid Guest Flow - ADR-001)
                .requestMatchers(HttpMethod.POST, "/api/reservations").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/reservations/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/reservations/*/cancel").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/reservations/events/*/availability").permitAll()
                // Documentation & Actuator
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                // All other management routes require authentication
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
```

### 4.3 Application Configuration Update for Resilience4j
In `application.yaml`:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      eventService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 10000ms
        ignoreExceptions:
          - com.seatflow.common.domain.exception.ResourceNotFoundException
          - com.seatflow.common.domain.exception.ValidationException
```

---

### 4.4 REST Controller Contract
`ReservationController.java` in `com.seatflow.reservation.web.controller`:

```java
package com.seatflow.reservation.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import com.seatflow.reservation.web.dto.response.ReservationResponse;
import com.seatflow.reservation.web.dto.response.SeatAvailabilityResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Reservations", description = "Seat reservation hold and availability management APIs")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @Operation(
        summary = "Create a 15-minute seat reservation hold",
        description = "Places a temporary 15-minute hold on selected seats (max 10 seats). Supports authenticated customers and unauthenticated guests (ADR-001)."
    )
    @ApiResponse(responseCode = "201", description = "Seat reservation hold created successfully",
        content = @Content(schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error, invalid email, or seat limit exceeded",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "One or more seats are already held or sold (Conflict)",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "Event catalog service unavailable",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ReservationResponse> createReservation(
            @Valid @RequestBody CreateReservationRequest request) {

        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);

        ReservationResponse response = reservationService.createReservation(request, authenticatedUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{reservationId}")
    @Operation(
        summary = "Get reservation details and hold expiration countdown",
        description = "Retrieves reservation hold status, expiry timestamp, and reserved seats."
    )
    @ApiResponse(responseCode = "200", description = "Reservation found",
        content = @Content(schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(responseCode = "404", description = "Reservation not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable UUID reservationId) {
        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        ReservationResponse response = reservationService.getReservationById(reservationId, authenticatedUserId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{reservationId}/cancel")
    @Operation(
        summary = "Cancel an active seat reservation hold",
        description = "Releases held seats immediately before the 15-minute expiration timer expires."
    )
    @ApiResponse(responseCode = "204", description = "Reservation hold cancelled successfully")
    @ApiResponse(responseCode = "400", description = "Reservation is not in PENDING status",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Reservation not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<Void> cancelReservation(@PathVariable UUID reservationId) {
        UUID authenticatedUserId = UserContext.getCurrentUserId()
                .map(UUID::fromString)
                .orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        reservationService.cancelReservation(reservationId, authenticatedUserId, isAdmin);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events/{eventId}/availability")
    @Operation(
        summary = "Get real-time seat availability for an event",
        description = "Returns list of currently held and sold seat statuses for the specified event."
    )
    @ApiResponse(responseCode = "200", description = "Seat availability list retrieved",
        content = @Content(schema = @Schema(implementation = SeatAvailabilityResponse.class)))
    public ResponseEntity<SeatAvailabilityResponse> getSeatAvailability(@PathVariable UUID eventId) {
        SeatAvailabilityResponse response = reservationService.getSeatAvailability(eventId);
        return ResponseEntity.ok(response);
    }
}
```

---

### 4.5 Web Slice Test Contract
`ReservationControllerTest`:
- Uses `@WebMvcTest(ReservationController.class)`, `@Import(SecurityConfig.class)`.
- Declares `@MockitoBean` for `ReservationService`, `JwtRoleConverter`, `JwtDecoder`.
- Tests:
  1. `POST /api/reservations` unauthenticated with valid guest email returns `201 CREATED`.
  2. `POST /api/reservations` authenticated with JWT Bearer resolves `userId` and returns `201 CREATED`.
  3. `POST /api/reservations` unauthenticated without guest email returns `400 BAD REQUEST`.
  4. `POST /api/reservations` with >10 seats returns `400 BAD REQUEST`.
  5. `POST /api/reservations` when service throws `ConflictException` returns `409 CONFLICT` with `ApiErrorResponse`.
  6. `GET /api/reservations/{id}` returns `200 OK` or `404 NOT FOUND`.
  7. `POST /api/reservations/{id}/cancel` returns `204 NO_CONTENT`.
  8. `GET /api/reservations/events/{eventId}/availability` returns `200 OK` with seat status array.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p04-004-rest-controllers-and-security-configuration` from `develop`.
2. Implement `RestClientConfig` configuring `eventRestClient` with correlation header propagation.
3. Configure `SecurityConfig` with OAuth2 Resource Server and public route matchers.
4. Implement `ReservationController` with full OpenAPI annotations and `ResponseEntity` responses.
5. Update `application.yaml` with Resilience4j circuit breaker properties and `ignoreExceptions` for `eventService`.
6. Write MockMvc controller slice tests in `ReservationControllerTest`.
7. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/reservation-service -Dtest=ReservationControllerTest
```

- [ ] `POST /api/reservations` handles both guest and authenticated requests.
- [ ] All endpoints return exact HTTP status codes (`201`, `200`, `204`, `400`, `404`, `409`).
- [ ] Security rules allow public checkout while maintaining token extraction.
- [ ] Task file is moved to `.ai/tasks/completed/phase-04-reservation-service/004-rest-controllers-and-security-configuration.md` when complete.
