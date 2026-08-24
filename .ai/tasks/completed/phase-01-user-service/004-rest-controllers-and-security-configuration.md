# TASK-P01-004: REST Controllers & Security Configuration

## 1. Task Metadata
- **Task ID:** `TASK-P01-004`
- **Git Branch:** `feat/p01-004-rest-controllers-and-security-configuration`
- **Target Module:** `backend/services/user-service`
- **Phase:** `Phase 01 - User Service`
- **Related Specs:** `.ai/architecture/04-authentication-security.md` (Section 3), `.ai/architecture/06-api-contracts.md` (Section 2.1), `.ai/architecture/01-common-modules.md` (Section 5: common-security)
- **Related ADRs:** None
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the `SecurityConfig` (OAuth2 Resource Server with `JwtRoleConverter` from `common-security`), `UserController` for authenticated user profile endpoints (`/api/users/me`), and `AdminUserController` for admin-only user listing (`/api/admin/users`). All controllers use complete OpenAPI/Swagger annotations.

### Critical Invariants to Enforce:
- [ ] Server-side authorization: Never rely solely on frontend route guards.
- [ ] `SecurityConfig` uses `JwtRoleConverter` from `common-security` — DO NOT create a custom role converter.
- [ ] NEVER create a `@RestControllerAdvice` — `GlobalExceptionHandler` from `common-observability` handles all errors.
- [ ] `/api/users/me` (GET, PUT) requires authentication (`authenticated()`).
- [ ] `/api/admin/users` requires `ROLE_ADMIN` (`hasRole("ADMIN")` or `@PreAuthorize`).
- [ ] Swagger/OpenAPI docs and Actuator endpoints are publicly accessible.
- [ ] Controllers are pure HTTP adapters — zero business logic.
- [ ] JWT claims (`sub`, `email`) extracted via `@AuthenticationPrincipal Jwt`.

---

## 3. Exact File Inventory

- `[NEW]` `src/main/java/com/seatflow/user/config/SecurityConfig.java`
- `[NEW]` `src/main/java/com/seatflow/user/web/controller/UserController.java`
- `[NEW]` `src/main/java/com/seatflow/user/web/controller/AdminUserController.java`

All paths relative to `backend/services/user-service/`.

---

## 4. Technical Specifications & Contracts

### 4.1 Security Configuration: `SecurityConfig`
```java
package com.seatflow.user.config;

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

### 4.2 Controller: `UserController` (`/api/users/me`)
```java
package com.seatflow.user.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.user.service.UserService;
import com.seatflow.user.web.dto.request.UpdateUserProfileRequest;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User Profile", description = "Authenticated user profile management")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(
        summary = "Get current user profile",
        description = "Returns the authenticated user's profile. Performs JIT provisioning if this is the user's first request."
    )
    @ApiResponse(responseCode = "200", description = "User profile retrieved successfully",
        content = @Content(schema = @Schema(implementation = UserProfileResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<UserProfileResponse> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String externalId = jwt.getSubject();
        String email = resolveEmail(jwt);

        UserProfileResponse response = userService.getOrCreateUserProfile(externalId, email);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/me")
    @Operation(
        summary = "Update current user profile",
        description = "Updates the authenticated user's profile details (phone). Performs JIT provisioning if the user does not exist."
    )
    @ApiResponse(responseCode = "200", description = "User profile updated successfully",
        content = @Content(schema = @Schema(implementation = UserProfileResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<UserProfileResponse> updateCurrentUser(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateUserProfileRequest request) {
        String externalId = jwt.getSubject();
        String email = resolveEmail(jwt);

        UserProfileResponse response = userService.updateUserProfile(externalId, email, request);
        return ResponseEntity.ok(response);
    }

    private String resolveEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            email = jwt.getClaimAsString("preferred_username");
        }
        return email;
    }
}
```

### 4.3 Controller: `AdminUserController` (`/api/admin/users`)
```java
package com.seatflow.user.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.user.service.UserService;
import com.seatflow.user.web.dto.response.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin - User Management", description = "Admin-only user management and listing APIs")
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "List all registered users (Admin)",
        description = "Returns a paginated list of all registered user profiles. Requires ROLE_ADMIN."
    )
    @ApiResponse(responseCode = "200", description = "Paginated user list retrieved successfully")
    @ApiResponse(responseCode = "401", description = "Authentication required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Admin role required",
        content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PagedResult<UserProfileResponse>> getAllUsers(
            @Parameter(description = "Page number (0-indexed)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort field", example = "createdAt")
            @RequestParam(defaultValue = "createdAt") String sort,
            @Parameter(description = "Sort direction (asc/desc)", example = "desc")
            @RequestParam(defaultValue = "desc") String direction) {

        Sort.Direction sortDirection = Sort.Direction.fromString(direction);
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));

        PagedResult<UserProfileResponse> result = userService.getAllUsers(pageable);
        return ResponseEntity.ok(result);
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p01-004-rest-controllers-and-security-configuration develop`
2. **Step 2 — SecurityConfig:** Create `SecurityConfig.java` with OAuth2 Resource Server, `JwtRoleConverter`, and endpoint authorization rules.
3. **Step 3 — UserController:** Create `UserController.java` with `GET /api/users/me` and `PUT /api/users/me` endpoints.
4. **Step 4 — AdminUserController:** Create `AdminUserController.java` with `GET /api/admin/users` endpoint and `@PreAuthorize("hasRole('ADMIN')")`.
5. **Step 5 — Verify Compilation:** Run `mvn clean compile -pl services/user-service -am` from `backend/`.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean compile -pl services/user-service -am
```
- [ ] `SecurityConfig` compiles and uses `JwtRoleConverter` from `common-security`.
- [ ] No `@RestControllerAdvice` created in this module.
- [ ] `UserController` extracts JWT claims via `@AuthenticationPrincipal Jwt` and delegates to `UserService`.
- [ ] `AdminUserController` enforces `@PreAuthorize("hasRole('ADMIN')")`.
- [ ] All endpoints documented with complete OpenAPI annotations.
- [ ] Task file is moved to `.ai/tasks/completed/phase-01-user-service/004-rest-controllers-and-security-configuration.md`.
