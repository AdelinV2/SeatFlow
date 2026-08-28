# 04 — Authentication & Security Architecture

SeatFlow externalizes user authentication and credential management to **Supabase Auth** (with standard OIDC/JWT, email/password, and Google federation per ADR-006) and enforces stateless, claims-based authorization via **JWT tokens**.

---

## 1. Identity & Auth Architecture

```text
┌────────────────┐         1. Authenticate (Supabase)          ┌─────────────────────────┐
│  Angular SPA   │ ──────────────────────────────────────────► │      Supabase Auth      │
│                │ ◄────────────────────────────────────────── │ (https://xxx.supabase.co)│
└───────┬────────┘          2. Access Token (JWT)              └─────────────────────────┘
        │
        │ 3. API Request + Authorization: Bearer <JWT>
        ▼
┌────────────────────────┐  4. Validate Signature (JWKS)
│      API Gateway       │ ──────────────────────────────────────────► [Supabase JWKS Endpoint]
│       Port 8080        │
└───────┬────────────────┘
        │ 5. Token Relay (Forward Header)
        ▼
┌────────────────────────┐
│ Downstream Service     │  6. Extract Principal via UserContext
│ (e.g. Reservation Svc) │  7. Server-Side RBAC: @PreAuthorize("hasRole('ADMIN')")
└────────────────────────┘
```

---

## 2. JWT Claims & Role Mapping

### 2.1 Standard JWT Payload Structure
```json
{
  "iss": "https://txyyirobwnomhxygbacq.supabase.co/auth/v1",
  "sub": "123e4567-e89b-12d3-a456-426614174000",
  "aud": "authenticated",
  "exp": 1756000000,
  "iat": 1755996400,
  "email": "customer@seatflow.com",
  "app_metadata": {
    "provider": "email",
    "providers": ["email"],
    "roles": [
      "ROLE_CUSTOMER"
    ]
  },
  "user_metadata": {
    "name": "Alex Smith"
  },
  "role": "authenticated"
}
```

### 2.2 Role Hierarchy (`SecurityRoles`)
- `ROLE_CUSTOMER` — Default role assigned to standard authenticated users (can hold seats, create payments, view own tickets).
- `ROLE_STAFF` — Assigned to venue entrance staff and gate stewards (can validate and scan tickets at entrance gates per ADR-005).
- `ROLE_ADMIN` — Assigned to platform administrators (can create events, modify venues, view platform sales metrics).

### 2.3 JWT Converter (`JwtRoleConverter`)
Located in `common-security`, automatically converts the `roles` (from `app_metadata.roles`, `user_metadata.roles`, or root `roles`) JSON array into Spring Security `GrantedAuthority` instances:
```java
@Component
public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = extractRoles(jwt);
        if (roles.isEmpty()) {
            return List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        }
        return roles.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
```

---

## 3. Server-Side Security & Context Extraction

### 3.1 Security Filter Chain (`SecurityConfig`)
Configured identically across all microservices (or customized per service requirements):
```java
import org.springframework.http.HttpMethod;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class ResourceServerConfig {

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(JwtRoleConverter jwtRoleConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtRoleConverter);
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtRoleConverter jwtRoleConverter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public catalog & seat availability
                .requestMatchers(HttpMethod.GET, "/api/events/**", "/api/venues/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/reservations/events/*/availability").permitAll()
                // Hybrid / Guest Checkout endpoints (support both guest & authenticated tokens)
                .requestMatchers(HttpMethod.POST, "/api/reservations").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/reservations/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/reservations/*/cancel").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/payments/intent").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/payments/*").permitAll()
                .requestMatchers("/api/payments/webhook").permitAll()
                // Guest ticket verification & download
                .requestMatchers("/api/tickets/guest/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/tickets/*/pdf").permitAll()
                // Gate scanner check-in endpoints (ADR-005)
                .requestMatchers("/api/scanner/tickets/**").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                // WebSockets & Docs
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/**").permitAll()
                // Admin endpoints
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Authenticated user profile & ticket management
                .requestMatchers("/api/tickets/my-tickets", "/api/users/**").authenticated()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(jwtRoleConverter)))
            );
        return http.build();
    }
}
```

### 3.2 Accessing Authenticated User vs Guest via `UserContext`
Endpoints supporting hybrid checkout check whether an authenticated principal is present:
```java
// Optional user extraction for hybrid endpoints:
Optional<String> maybeUserId = UserContext.getCurrentUserId();

if (maybeUserId.isPresent()) {
    String userId = maybeUserId.get();
    // Resolve email and name from JWT or User Profile
} else {
    // Guest flow: validate that customerEmail is present and valid
    if (request.customerEmail() == null || request.customerEmail().isBlank()) {
        throw new ValidationException("Customer email is required for guest checkout", List.of());
    }
}
```

---

## 4. Frontend Security & Route Guards

- Angular route guards (`admin.guard.ts`) protect administrative areas.
- Public customer routes (`/`, `/events/:id`, `/events/:id/seats`, `/checkout/:reservationId`, `/order-confirmation/:paymentId`, `/tickets/guest/:code`) are open to guests without redirection to login.
- Profile routes (`/profile/tickets`, `/profile/settings`) use `auth.guard.ts` to redirect unauthenticated visitors to Entra OIDC login.
- Every API endpoint enforces authorization server-side.
- Tokens are held in-memory or secure storage and attached via `auth.interceptor.ts` whenever an active session exists.

---

## 5. Non-Negotiable Security Invariants

1. **Zero Secrets in Git:** Database credentials, Stripe secret keys, and OIDC client secrets are injected exclusively via environment variables (`${DB_PASSWORD}`, `${STRIPE_SECRET_KEY}`).
2. **Stripe Webhook Signature Verification:** All payment webhooks must verify the `Stripe-Signature` header against the configured webhook secret.
3. **No Direct Entity Exposure:** Never return JPA entities in REST responses. Always map through MapStruct DTO records.
4. **API Gateway Rate Limiting for Guest Checkouts:** Public endpoints like `POST /api/reservations` must be rate-limited by client IP in `api-gateway` via Redis to prevent automated seat reservation denial-of-service.
5. **Secure Guest Ticket Access:** Guest ticket viewing and PDF downloads require validation of the unique, cryptographically secure `ticket_code` (and optional HMAC token) delivered via email to prevent enumeration.

