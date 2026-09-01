# ADR-006: Adoption of Supabase Auth as Identity & Authentication Provider

- **Date:** 2026-08-28
- **Author(s):** SeatFlow Core Architecture Team
- **Driven by Task:** `TASK-P09-002` (Core Auth, Interceptors & Navigation Shell)
- **Supersedes:** Sections of `.ai/architecture/04-authentication-security.md` referencing Microsoft Entra External ID

## 1. Status
`ACCEPTED`

## 2. Context
SeatFlow was originally specified to use **Microsoft Entra External ID (CIAM)** for customer authentication, OIDC token issuance, and user management. However, several operational and commercial constraints emerged during frontend implementation:
1. **Credit Card & Billing Requirement:** Creating and linking a tenant in Microsoft Entra External ID mandates an active Microsoft Azure Subscription, which enforces credit card verification and carries the risk of unintended cloud consumption billing.
2. **Setup Friction & Developer Experience (DX):** Configuring App Registrations, Enterprise Applications, API Scopes, and External ID user flows across multiple Azure admin portals is cumbersome and error-prone for local development.
3. **Requirement:** SeatFlow requires an OIDC-compliant authentication solution that is:
   - **100% Free** with zero credit card requirements for development and testing.
   - Generous free tier for live deployment (at least 50,000 Monthly Active Users).
   - Standard JWT issuance with JWKS asymmetric key validation compatible with Spring Boot Resource Server and Angular SPA.
   - Granular role-based claims mapping (`ROLE_CUSTOMER`, `ROLE_STAFF`, `ROLE_ADMIN`).

## 3. Decision
We have decided to adopt **Supabase Auth** as the managed Identity and Authentication Provider for SeatFlow:

1. **Client-Side Authentication (Angular):**
   - The Angular frontend uses `@supabase/supabase-js` or standard REST OIDC login to handle user signup, sign-in (email/password and social OAuth providers), session refresh, and token management.
   - The JWT `access_token` issued by Supabase is attached by `authInterceptor` to all outgoing `/api/**` requests as `Authorization: Bearer <token>`.

2. **Server-Side Validation (Spring Boot Microservices):**
   - Microservices retain the **OAuth2 Resource Server** architecture without architectural refactoring.
   - Tokens are validated against Supabase's standard JWKS endpoint:
     ```text
     SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json
     ```
   - `JwtRoleConverter` in `backend/common/common-security` is enhanced to extract roles from Supabase's `app_metadata.roles` or `user_metadata.roles` claims (alongside root `roles` array).

3. **User Identification & Subject Mapping:**
   - The standard `sub` claim in Supabase JWTs contains the user's UUID, matching SeatFlow's `external_id` column in `seatflow_user.users`.

## 4. Alternatives Considered
1. **Microsoft Entra External ID (Original Spec):**
   - *Pros:* Deep enterprise integration, Microsoft ecosystem backing.
   - *Cons:* Mandates credit card verification, requires Azure subscription, high portal configuration complexity.
   - *Reason Rejected:* Unnecessary financial friction and complex setup for project goals.

2. **Self-Hosted Keycloak in Docker:**
   - *Pros:* 100% free, full self-hosting control, runs in local docker-compose.
   - *Cons:* Additional memory footprint on local machines (~500MB+ RAM), requires managing Keycloak upgrades and DB schemas in production.
   - *Reason Rejected:* Supabase Auth provides a managed cloud experience with zero local resource overhead and instant user management.

3. **Clerk / Auth0:**
   - *Pros:* High quality developer experience.
   - *Cons:* Auth0 free tier is limited to 7,500 MAU; Clerk free tier is limited to 10,000 MAU.
   - *Reason Rejected:* Supabase provides 50,000 MAU free tier matching Microsoft Entra's capacity with zero credit card requirements.

## 5. Consequences
### Positive:
- **Zero Cost & No Credit Card:** 100% free forever for development and up to 50,000 MAU in staging/production without financial risk.
- **Simplified Setup:** Project creation and user provisioning takes under 2 minutes via Supabase dashboard.
- **Architectural Preservation:** Zero changes required to Spring Boot controller security annotations (`@PreAuthorize("hasRole('ADMIN')")`), API Gateway routing, or Angular route guards (`authGuard`, `staffGuard`, `adminGuard`).
- **Open-Source Standard:** Supabase Auth is open-source (GoTrue), allowing future self-hosting if ever required.

### Negative / Trade-offs:
- **Claim Extraction Path:** Role claims in Supabase reside in `app_metadata.roles` rather than root `roles`.
  - *Mitigation:* `JwtRoleConverter` in `common-security` is updated to check `app_metadata.roles`, `user_metadata.roles`, and root `roles` transparently.

## 6. Implementation Notes
- **`backend/common/common-security`:** Update `JwtRoleConverter.java` to support nested JSON claim paths (`app_metadata` -> `roles`).
- **`frontend/`:** Install `@supabase/supabase-js` and configure `AuthService` in `TASK-P09-002`.
- **Environment Templates:** Update `.env.example` across microservices to use `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`.
- **Related Tasks:**
  - [002-core-auth-oidc-interceptors-and-nav-shell.md](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/.ai/tasks/phase-09-frontend-portal/002-core-auth-oidc-interceptors-and-nav-shell.md)
