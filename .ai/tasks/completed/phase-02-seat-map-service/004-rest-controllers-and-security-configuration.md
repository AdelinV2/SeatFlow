# TASK-P02-004: REST Controllers & Security Configuration

## 1. Task Metadata
- **Task ID:** `TASK-P02-004`
- **Git Branch:** `feat/p02-004-rest-controllers-and-security-configuration`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 02 - Seat Map & Venue Service`
- **Related Specs:** `.ai/architecture/04-authentication-security.md` (Section 3), `.ai/architecture/06-api-contracts.md` (Section 2.2), `.ai/architecture/01-common-modules.md` (Section 5: common-security)
- **Related ADRs:** None
- **Dependencies:** `TASK-P02-003` (Service layer must exist)
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the `SecurityConfig` (OAuth2 Resource Server with `JwtRoleConverter` from `common-security`), `VenueController` for public venue browsing endpoints (`/api/venues/**`), and `AdminVenueController` for admin-only venue/section/seat management endpoints (`/api/admin/venues/**`). All controllers use complete OpenAPI/Swagger annotations.

### Critical Invariants to Enforce:
- [ ] Server-side authorization: Never rely solely on frontend route guards.
- [ ] `SecurityConfig` uses `JwtRoleConverter` from `common-security` — DO NOT create a custom role converter.
- [ ] NEVER create a `@RestControllerAdvice` — `GlobalExceptionHandler` from `common-observability` handles all errors.
- [ ] `GET /api/venues/**` → `permitAll()` (public browsing).
- [ ] `/api/admin/**` → `hasRole("ADMIN")` (admin-only management).
- [ ] Swagger/OpenAPI docs and Actuator endpoints are publicly accessible.
- [ ] Controllers are pure HTTP adapters — zero business logic.
- [ ] All REST endpoints return `ResponseEntity<T>` with explicit HTTP status codes.
- [ ] `@Valid` on every `@RequestBody`.
- [ ] Constructor injection with `@RequiredArgsConstructor` (never `@Autowired` on fields).

---

## 3. Exact File Inventory

All paths relative to `backend/services/seat-map-service/`.

- `[NEW]` `src/main/java/com/seatflow/seatmap/config/SecurityConfig.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/controller/VenueController.java`
- `[NEW]` `src/main/java/com/seatflow/seatmap/web/controller/AdminVenueController.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Security Configuration: `SecurityConfig`
```java
package com.seatflow.seatmap.config;

import com.seatflow.common.security.converter.JwtRoleConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtRoleConverter jwtRoleConverter;

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Swagger / OpenAPI / Actuator — publicly accessible
                .requestMatchers(
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/actuator/**"
                ).permitAll()
                // Public venue browsing — NO authentication required
                .requestMatchers("/api/venues/**").permitAll()
                // Admin endpoints — ROLE_ADMIN required
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // All other endpoints require authentication
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );
        return http.build();
    }
}
```

### 4.2 Public Controller: `VenueController` (`/api/venues`)
```java
package com.seatflow.seatmap.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.seatmap.service.SeatMapLayoutService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.response.VenueDetailResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSeatMapLayoutResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
@Tag(name = "Venues (Public)", description = "Public venue browsing and seat map layout APIs")
public class VenueController {

    private final VenueService venueService;
    private final SeatMapLayoutService seatMapLayoutService;

    @GetMapping
    @Operation(
        summary = "List all venues",
        description = "Returns a paginated list of all venues. Supports optional filtering by city and name search."
    )
    @ApiResponse(responseCode = "200", description = "Venues retrieved successfully")
    public ResponseEntity<PagedResult<VenueResponse>> listVenues(
            @Parameter(description = "Filter by city") @RequestParam(required = false) String city,
            @Parameter(description = "Search by name (partial, case-insensitive)") @RequestParam(required = false) String name,
            @PageableDefault(size = 20) Pageable pageable) {
        PagedResult<VenueResponse> result = venueService.listVenues(city, name, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{venueId}")
    @Operation(
        summary = "Get venue details",
        description = "Returns detailed venue information including sections and active seat counts."
    )
    @ApiResponse(responseCode = "200", description = "Venue retrieved successfully",
        content = @Content(schema = @Schema(implementation = VenueDetailResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueDetailResponse> getVenueById(@PathVariable UUID venueId) {
        VenueDetailResponse response = venueService.getVenueById(venueId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{venueId}/layout")
    @Operation(
        summary = "Get venue seat map layout",
        description = "Returns the complete venue seat map including all sections and their active seats with grid coordinates. Used by the interactive seat map UI."
    )
    @ApiResponse(responseCode = "200", description = "Venue layout retrieved successfully",
        content = @Content(schema = @Schema(implementation = VenueSeatMapLayoutResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueSeatMapLayoutResponse> getVenueLayout(@PathVariable UUID venueId) {
        VenueSeatMapLayoutResponse response = seatMapLayoutService.getVenueLayout(venueId);
        return ResponseEntity.ok(response);
    }
}
```

### 4.3 Admin Controller: `AdminVenueController` (`/api/admin/venues`)
```java
package com.seatflow.seatmap.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.seatmap.service.VenueSectionService;
import com.seatflow.seatmap.service.VenueService;
import com.seatflow.seatmap.web.dto.request.CreateVenueRequest;
import com.seatflow.seatmap.web.dto.request.CreateVenueSectionRequest;
import com.seatflow.seatmap.web.dto.request.UpdateSeatStatusRequest;
import com.seatflow.seatmap.web.dto.request.UpdateVenueRequest;
import com.seatflow.seatmap.web.dto.response.SeatResponse;
import com.seatflow.seatmap.web.dto.response.VenueResponse;
import com.seatflow.seatmap.web.dto.response.VenueSectionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/venues")
@RequiredArgsConstructor
@Tag(name = "Venues (Admin)", description = "Admin-only venue and section management APIs")
public class AdminVenueController {

    private final VenueService venueService;
    private final VenueSectionService sectionService;

    @PostMapping
    @Operation(
        summary = "Create a new venue",
        description = "Creates a new venue. Rejects duplicate (name, city) combinations."
    )
    @ApiResponse(responseCode = "201", description = "Venue created successfully",
        content = @Content(schema = @Schema(implementation = VenueResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Venue with same name already exists in the city",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueResponse> createVenue(@Valid @RequestBody CreateVenueRequest request) {
        VenueResponse response = venueService.createVenue(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{venueId}")
    @Operation(
        summary = "Update an existing venue",
        description = "Updates venue details. Only non-null fields in the request body are applied."
    )
    @ApiResponse(responseCode = "200", description = "Venue updated successfully",
        content = @Content(schema = @Schema(implementation = VenueResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueResponse> updateVenue(
            @PathVariable UUID venueId,
            @Valid @RequestBody UpdateVenueRequest request) {
        VenueResponse response = venueService.updateVenue(venueId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{venueId}/sections")
    @Operation(
        summary = "Create a venue section with auto-generated seat grid",
        description = "Creates a new section in the specified venue and automatically generates a rowCount × colCount seat grid. Row labels use alphabetic progression (A, B, C, ..., Z, AA, AB, ...)."
    )
    @ApiResponse(responseCode = "201", description = "Section created with seat grid",
        content = @Content(schema = @Schema(implementation = VenueSectionResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Section with same name already exists in this venue",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<VenueSectionResponse> createSection(
            @PathVariable UUID venueId,
            @Valid @RequestBody CreateVenueSectionRequest request) {
        VenueSectionResponse response = sectionService.createSection(venueId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{venueId}/sections/{sectionId}/seats/{seatId}")
    @Operation(
        summary = "Toggle seat active/inactive status",
        description = "Activates or deactivates a specific seat within a venue section. Deactivated seats are not bookable."
    )
    @ApiResponse(responseCode = "200", description = "Seat status updated",
        content = @Content(schema = @Schema(implementation = SeatResponse.class)))
    @ApiResponse(responseCode = "404", description = "Venue, section, or seat not found",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<SeatResponse> updateSeatStatus(
            @PathVariable UUID venueId,
            @PathVariable UUID sectionId,
            @PathVariable UUID seatId,
            @Valid @RequestBody UpdateSeatStatusRequest request) {
        SeatResponse response = sectionService.updateSeatStatus(venueId, sectionId, seatId, request);
        return ResponseEntity.ok(response);
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p02-004-rest-controllers-and-security-configuration develop`
2. **Step 2 — SecurityConfig:** Create `SecurityConfig.java` with `JwtRoleConverter` from `common-security`. Configure: `permitAll()` for `/api/venues/**`, Swagger, and Actuator; `hasRole("ADMIN")` for `/api/admin/**`; `authenticated()` for all other endpoints.
3. **Step 3 — VenueController:** Create public controller for `GET /api/venues`, `GET /api/venues/{venueId}`, `GET /api/venues/{venueId}/layout` with full OpenAPI annotations.
4. **Step 4 — AdminVenueController:** Create admin controller for `POST /api/admin/venues`, `PUT /api/admin/venues/{venueId}`, `POST /api/admin/venues/{venueId}/sections`, `PATCH /api/admin/venues/{venueId}/sections/{sectionId}/seats/{seatId}` with `@Valid` on all request bodies.
5. **Step 5 — Verify Compilation:** Run `mvn clean compile -pl services/seat-map-service -am` from `backend/`.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean compile -pl services/seat-map-service -am
```
- [ ] `SecurityConfig` compiles and uses `JwtRoleConverter` from `common-security`.
- [ ] `GET /api/venues/**` is configured as `permitAll()`.
- [ ] `/api/admin/**` requires `ROLE_ADMIN`.
- [ ] No `@RestControllerAdvice` created in this module.
- [ ] All controllers use `@RequiredArgsConstructor` for injection, `@Valid` on request bodies, and return `ResponseEntity<T>`.
- [ ] All endpoints have complete OpenAPI `@Operation`, `@ApiResponse`, `@Schema` annotations.
- [ ] Admin controller returns `201 Created` for POST operations.
- [ ] Task file is moved to `.ai/tasks/completed/phase-02-seat-map-service/004-rest-controllers-and-security-configuration.md`.
