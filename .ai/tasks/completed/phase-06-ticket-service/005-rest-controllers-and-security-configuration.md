# TASK-P06-005: REST Controllers, Security Configuration & WebMvc Test Suite

## 1. Task Metadata
- **Task ID:** `TASK-P06-005`
- **Git Branch:** `feat/p06-005-rest-controllers-and-security-configuration`
- **Target Module:** `backend/services/ticket-service`
- **Phase:** `Phase 06 - Ticket & QR Code Service`
- **Related Specs:** `.ai/architecture/04-authentication-security.md`, `.ai/architecture/06-api-contracts.md` (Section 2.6)
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-004-stripe-tax-and-tax-inclusive-pricing.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Expose the HTTP REST endpoints for customer ticket dashboards ("My Tickets"), detailed ticket views with QR code data, unauthenticated guest ticket lookup via unique ticket codes (ADR-001), streaming PDF ticket downloads, and gate entrance scanner validation. Configure Spring Security OAuth2 Resource Server integration with `JwtRoleConverter` enforcing role-based endpoint access (`ROLE_CUSTOMER`, `ROLE_ADMIN`).

### Critical Invariants to Enforce:
- [ ] **Hybrid Guest Delivery (ADR-001):** `GET /api/tickets/guest/{ticketCode}` must be publicly accessible without authentication.
- [ ] **PDF Download Endpoint:** `GET /api/tickets/{ticketId}/pdf` returns `Content-Type: application/pdf` and `Content-Disposition: inline; filename="ticket-<id>.pdf"`.
- [ ] **Gate Scanner Authorization:** `POST /api/admin/tickets/validate` is restricted exclusively to authenticated users with `ROLE_ADMIN` (or venue gate scanner role).
- [ ] **Customer Privacy & Authorization:** `GET /api/tickets/my-tickets` extracts the caller's `userId` from `UserContext` and rejects unauthenticated requests with HTTP 401.
- [ ] **Pure HTTP Adapters:** Controllers contain zero direct database access. All input validation uses Jakarta `@Valid`, and methods return `ResponseEntity<T>` with explicit HTTP status codes.
- [ ] **Centralized Exception Handling:** No `@RestControllerAdvice` or custom error envelopes in `ticket-service`; relies entirely on auto-configured `GlobalExceptionHandler` from `common-observability`.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/config/SecurityConfig.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/controller/TicketController.java`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/controller/TicketAdminController.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/web/controller/TicketControllerTest.java`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/web/controller/TicketAdminControllerTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Security Configuration
`config/SecurityConfig.java`:
```java
package com.seatflow.ticket.config;

import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.converter.JwtRoleConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtRoleConverter jwtRoleConverter;

    public SecurityConfig(JwtRoleConverter jwtRoleConverter) {
        this.jwtRoleConverter = jwtRoleConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public Actuator and Swagger documentation
                .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // Guest ticket delivery (ADR-001) and public PDF download
                .requestMatchers(HttpMethod.GET, "/api/tickets/guest/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tickets/*/pdf").permitAll()

                // Gate scanner admin verification
                .requestMatchers("/api/admin/tickets/**").hasAuthority(SecurityRoles.ROLE_ADMIN)

                // Customer authenticated endpoints
                .requestMatchers(HttpMethod.GET, "/api/tickets/my-tickets").hasAuthority(SecurityRoles.ROLE_CUSTOMER)
                .requestMatchers(HttpMethod.GET, "/api/tickets/*").authenticated()

                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtRoleConverter))
            )
            .build();
    }
}
```

---

### 4.2 Ticket REST Controller
`web/controller/TicketController.java`:
```java
package com.seatflow.ticket.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.security.SecurityRoles;
import com.seatflow.common.security.context.UserContext;
import com.seatflow.ticket.service.TicketService;
import com.seatflow.ticket.web.dto.response.TicketDetailResponse;
import com.seatflow.ticket.web.dto.response.TicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@Tag(name = "Tickets", description = "Customer and guest digital ticket delivery APIs")
public class TicketController {

    private final TicketService ticketService;

    @GetMapping("/my-tickets")
    @Operation(summary = "List tickets for authenticated user", description = "Retrieves paginated tickets belonging to the current user")
    @ApiResponse(responseCode = "200", description = "Tickets retrieved successfully")
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<PagedResult<TicketResponse>> getMyTickets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID userId = UserContext.getCurrentUserIdAsUuid()
                .orElseThrow(() -> new BusinessException("User authentication required", ErrorCode.UNAUTHORIZED, 401));

        PagedResult<TicketResponse> result = ticketService.getMyTickets(userId, PageRequest.of(page, size));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/guest/{ticketCode}")
    @Operation(summary = "Retrieve ticket by guest code", description = "Public endpoint for guest ticket delivery via unique token (ADR-001)")
    @ApiResponse(responseCode = "200", description = "Ticket details with QR payload")
    @ApiResponse(responseCode = "404", description = "Ticket code not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<TicketDetailResponse> getGuestTicket(@PathVariable String ticketCode) {
        TicketDetailResponse response = ticketService.getGuestTicketByCode(ticketCode);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{ticketId}")
    @Operation(summary = "Retrieve ticket details by ID", description = "Customer or admin view of ticket with QR code data")
    @ApiResponse(responseCode = "200", description = "Ticket details retrieved")
    @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<TicketDetailResponse> getTicketById(@PathVariable UUID ticketId) {
        UUID userId = UserContext.getCurrentUserIdAsUuid().orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        TicketDetailResponse response = ticketService.getTicketById(ticketId, userId, isAdmin);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{ticketId}/pdf")
    @Operation(summary = "Download ticket as PDF", description = "Renders and streams official PDF ticket with embedded QR code and fiscal breakdown")
    @ApiResponse(responseCode = "200", description = "PDF stream returned", content = @Content(mediaType = "application/pdf"))
    @ApiResponse(responseCode = "404", description = "Ticket not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<byte[]> downloadTicketPdf(@PathVariable UUID ticketId) {
        UUID userId = UserContext.getCurrentUserIdAsUuid().orElse(null);
        boolean isAdmin = UserContext.hasRole(SecurityRoles.ROLE_ADMIN);

        byte[] pdfBytes = ticketService.generateTicketPdf(ticketId, userId, isAdmin);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ticket-" + ticketId + ".pdf\"")
                .body(pdfBytes);
    }
}
```

---

### 4.3 Ticket Admin Controller
`web/controller/TicketAdminController.java`:
```java
package com.seatflow.ticket.web.controller;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.ticket.service.TicketService;
import com.seatflow.ticket.web.dto.request.ValidateTicketRequest;
import com.seatflow.ticket.web.dto.response.ValidationResultResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/tickets")
@RequiredArgsConstructor
@Tag(name = "Admin Tickets", description = "Venue gate scanner check-in APIs")
public class TicketAdminController {

    private final TicketService ticketService;

    @PostMapping("/validate")
    @Operation(summary = "Validate ticket at entrance gate", description = "Scans ticket code, validates status, records validation audit log, and marks ticket USED")
    @ApiResponse(responseCode = "200", description = "Validation result returned")
    @ApiResponse(responseCode = "400", description = "Invalid request payload", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden — requires ROLE_ADMIN", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ValidationResultResponse> validateTicket(
            @Valid @RequestBody ValidateTicketRequest request) {
        ValidationResultResponse response = ticketService.validateTicket(request);
        return ResponseEntity.ok(response);
    }
}
```

---

### 4.4 WebMvc Test Suite Contract

#### `TicketControllerTest`:
- Uses `@WebMvcTest(TicketController.class)`.
- Injects `@MockBean TicketService`.
- Tests:
  - `getMyTickets_withCustomerRole_returns200`: Mocks `UserContext` with customer UUID and verifies JSON list.
  - `getMyTickets_unauthenticated_returns401`.
  - `getGuestTicket_publicAccess_returns200`: Requests `/api/tickets/guest/SF-TKT-1234` without auth headers and asserts 200 OK.
  - `downloadPdf_returnsApplicationPdfAndInlineHeader`: Verifies `Content-Type: application/pdf` and `Content-Disposition`.

#### `TicketAdminControllerTest`:
- Uses `@WebMvcTest(TicketAdminController.class)`.
- Injects `@MockBean TicketService`.
- Tests:
  - `validateTicket_withAdminRole_returns200`: Validates payload with scanner ID, asserts result JSON.
  - `validateTicket_withCustomerRole_returns403`: Asserts standard customer is forbidden from scanner endpoint.
  - `validateTicket_unauthenticated_returns401`.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p06-005-rest-controllers-and-security-configuration` from `develop`.
2. Implement `SecurityConfig.java` configuring OAuth2 Resource Server and route permissions.
3. Implement `TicketController.java` exposing `/api/tickets/my-tickets`, `/api/tickets/{ticketId}`, `/api/tickets/guest/{ticketCode}`, and `/api/tickets/{ticketId}/pdf`.
4. Implement `TicketAdminController.java` exposing `/api/admin/tickets/validate`.
5. Write slice tests `TicketControllerTest` and `TicketAdminControllerTest`.
6. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/ticket-service -Dtest=*ControllerTest
```

- [ ] All controllers and security configurations compile cleanly.
- [ ] Public guest ticket delivery and PDF streaming endpoints respond correctly without authentication.
- [ ] Authenticated customer endpoints enforce `ROLE_CUSTOMER` and extract user context.
- [ ] Entrance validation endpoint is secured with `ROLE_ADMIN`.
- [ ] Task file is moved to `.ai/tasks/completed/phase-06-ticket-service/005-rest-controllers-and-security-configuration.md` when complete.
