# TASK-P08-006: Admin REST Controller, Security Configuration & Full Integration Test Suite

## 1. Task Metadata
- **Task ID:** `TASK-P08-006`
- **Git Branch:** `feat/p08-001-notification-service`
- **Target Module:** `backend/services/notification-service`
- **Phase:** `Phase 08 - Notification Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/04-authentication-security.md`, `.ai/architecture/06-api-contracts.md`
- **Related ADRs:** `None`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Implement the administrative REST controller (`NotificationAdminController`) exposing endpoints to audit notification logs (`GET /api/admin/notifications`, `GET /api/admin/notifications/{id}`), configure Spring Security OAuth2 Resource Server (`SecurityConfig`) with JWT validation and `JwtRoleConverter`, and develop a comprehensive end-to-end integration test (`NotificationServiceIntegrationTest`) testing Kafka event consumption, mock Resend REST dispatch, and database log verification with Testcontainers.

### Critical Invariants to Enforce:
- [x] **Role-Based Authorization:** All administrative endpoints under `/api/admin/notifications/**` require `ROLE_ADMIN`.
- [x] **No Controller Advice in Service:** Use auto-configured `GlobalExceptionHandler` from `common-observability`.
- [x] **Pure HTTP Adapters:** Controllers contain zero domain business logic and return `ResponseEntity<T>`.
- [x] **Comprehensive Test Coverage:** End-to-end integration test verifying complete flow: Kafka event publish -> Consumer -> Resend Mock -> Database state.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/config/SecurityConfig.java`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/web/controller/NotificationAdminController.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/web/controller/NotificationAdminControllerTest.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/integration/NotificationServiceIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 REST Endpoints
- `GET /api/admin/notifications?recipientEmail={email}&page=0&size=20` -> `PagedResult<NotificationLogResponse>`
- `GET /api/admin/notifications/{id}` -> `NotificationLogResponse`

---

## 5. Step-by-Step Implementation Sequence
1. Create `SecurityConfig.java` configuring OAuth2 Resource Server and `ROLE_ADMIN` checks.
2. Implement `NotificationAdminController.java` with OpenAPI documentation and pagination.
3. Write `@WebMvcTest` `NotificationAdminControllerTest` testing RBAC and pagination.
4. Write `NotificationServiceIntegrationTest` using PostgreSQL Testcontainers, Kafka Testcontainers, and Mock Resend server.
5. Run full Maven verification (`mvn clean verify -pl backend/services/notification-service`).

---

## 6. Definition of Done & Verification Command
```bash
mvn clean verify -pl backend/services/notification-service
```
