# 04 — Authentication & Security Architecture

SeatFlow externalizes user authentication and credential management to **Microsoft Entra External ID** (with OIDC and Google/Email federation) and enforces stateless, claims-based authorization via **JWT tokens**.

---

## 1. Identity & Auth Architecture

```text
┌────────────────┐          1. Authenticate (OIDC)          ┌─────────────────────────┐
│  Angular SPA   │ ───────────────────────────────────────► │ Entra External ID (OIDC)│
│                │ ◄─────────────────────────────────────── │                         │
└───────┬────────┘          2. Access Token (JWT)           └─────────────────────────┘
        │
        │ 3. API Request + Authorization: Bearer <JWT>
        ▼
┌────────────────────────┐  4. Validate Signature (JWKS)
│      API Gateway       │ ───────────────────────────────► [Microsoft JWKS Endpoint]
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
  "iss": "https://seatflow.ciamlogin.com/.../v2.0",
  "sub": "123e4567-e89b-12d3-a456-426614174000",
  "aud": "api://seatflow-backend",
  "exp": 1756000000,
  "iat": 1755996400,
  "email": "customer@seatflow.com",
  "name": "Alex Smith",
  "roles": [
    "ROLE_CUSTOMER"
  ]
}
```

### 2.2 Role Hierarchy (`SecurityRoles`)
- `ROLE_CUSTOMER` — Default role assigned to standard authenticated users (can hold seats, create payments, view own tickets).
- `ROLE_ADMIN` — Assigned to platform administrators (can create events, modify venues, view platform sales metrics).

### 2.3 JWT Converter (`JwtRoleConverter`)
Located in `common-security`, automatically converts the `roles` JSON array into Spring Security `GrantedAuthority` instances:
```java
@Component
public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
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
Configured identically across all microservices:
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
                .requestMatchers(HttpMethod.GET, "/api/events/**", "/api/venues/**").permitAll()
                .requestMatchers("/api/payments/webhook", "/ws/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(jwtRoleConverter)))
            );
        return http.build();
    }
}
```

### 3.2 Accessing Authenticated User via `UserContext`
```java
String userId = UserContext.getCurrentUserId()
        .orElseThrow(() -> new BusinessException("User ID not present in token", ErrorCode.UNAUTHORIZED, 401));

if (!UserContext.hasRole(SecurityRoles.ROLE_ADMIN) && !userId.equals(requestedResourceUserId)) {
    throw new BusinessException("Access denied to requested resource", ErrorCode.FORBIDDEN, 403);
}
```

---

## 4. Frontend Security & Route Guards

- Angular route guards (`auth.guard.ts`, `admin.guard.ts`) are strictly for UI redirection.
- Every API endpoint enforces authorization server-side.
- Tokens are held in-memory or secure storage and attached via `auth.interceptor.ts`.

---

## 5. Non-Negotiable Security Invariants

1. **Zero Secrets in Git:** Database credentials, Stripe secret keys, and OIDC client secrets are injected exclusively via environment variables (`${DB_PASSWORD}`, `${STRIPE_SECRET_KEY}`).
2. **Stripe Webhook Signature Verification:** All payment webhooks must verify the `Stripe-Signature` header against the configured webhook secret.
3. **No Direct Entity Exposure:** Never return JPA entities in REST responses. Always map through MapStruct DTO records.
