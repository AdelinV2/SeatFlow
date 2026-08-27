# TASK-P06-008: Refactor Gate Check-In to TicketScannerController and Introduce ROLE_STAFF

## 1. Task Metadata
- **Task ID:** `TASK-P06-008`
- **Git Branch:** `feat/p06-008-staff-scanner-controller-and-role-authorization`
- **Target Module:** `../../../backend/services/ticket-service`, `../../../backend/common/common-security`, `../../../backend/services/api-gateway`
- **Phase:** `Phase 06 - Ticket Service`
- **Related Specs:** `../../architecture/04-authentication-security.md`, `../../architecture/06-api-contracts.md`, `../../architecture/07-frontend-specification.md`
- **Related ADRs:** `../../decisions/ADR-005-venue-gate-check-in-and-staff-scanner-authorization.md`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Refactor ticket gate validation from the administrative path (`/api/admin/tickets/validate`) to a dedicated staff scanner controller (`/api/scanner/tickets/validate`) and introduce `ROLE_STAFF` authority in `common-security` to enforce the Principle of Least Privilege for gate stewards. Update API Gateway route predicates.

### Critical Invariants to Enforce:
- [x] `ROLE_STAFF` and `ROLE_ADMIN` are both authorized to validate tickets via `/api/scanner/tickets/validate`.
- [x] Customers (`ROLE_CUSTOMER`) and unauthenticated requests must be rejected with 403 Forbidden / 401 Unauthorized.
- [x] Ticket state transitions to `USED` upon successful validation with an audit log in `ticket_validations`.
- [x] API Gateway routes `/api/scanner/tickets/**` to `ticket-service`.

---

## 3. Exact File Inventory

### Shared & Gateway:
- `[MODIFY]` `../../../backend/common/common-security/src/main/java/com/seatflow/common/security/SecurityRoles.java`
- `[MODIFY]` `../../../backend/services/api-gateway/src/main/resources/application.yaml`

### Ticket Service:
- `[NEW]` `../../../backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/controller/TicketScannerController.java`
- `[DELETE]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/web/controller/TicketAdminController.java`
- `[MODIFY]` `../../../backend/services/ticket-service/src/main/java/com/seatflow/ticket/config/SecurityConfig.java`
- `[NEW]` `../../../backend/services/ticket-service/src/test/java/com/seatflow/ticket/web/controller/TicketScannerControllerTest.java`
- `[DELETE]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/web/controller/TicketAdminControllerTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Security Roles (`SecurityRoles.java`)
```java
public static final String ROLE_STAFF = "ROLE_STAFF";
public static final String STAFF = "STAFF";
```

### 4.2 Endpoint Contract (`TicketScannerController`)
- **HTTP Method:** `POST`
- **Path:** `/api/scanner/tickets/validate`
- **Authorized Authorities:** `ROLE_STAFF`, `ROLE_ADMIN`
- **Request Body:** `ValidateTicketRequest(ticketCode, scannerDeviceId)`
- **Response Body:** `ValidationResultResponse`

### 4.3 Gateway Route Predicate (`api-gateway/application.yaml`)
```yaml
- id: ticket-service
  uri: lb://ticket-service
  predicates:
    - Path=/api/tickets/**, /api/scanner/tickets/**, /api/admin/tickets/**
```

---

## 5. Step-by-Step Implementation Sequence
1. Step 1 - Add `ROLE_STAFF` to `SecurityRoles.java` in `common-security`.
2. Step 2 - Add `/api/scanner/tickets/**` to `api-gateway` `application.yaml`.
3. Step 3 - Create `TicketScannerController.java` with OpenAPI annotations.
4. Step 4 - Remove deprecated `TicketAdminController.java`.
5. Step 5 - Update `SecurityConfig.java` in `ticket-service`.
6. Step 6 - Create `TicketScannerControllerTest.java` with MockMvc security tests.
7. Step 7 - Remove deprecated `TicketAdminControllerTest.java`.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```powershell
mvn test -f backend/services/ticket-service/pom.xml
```
- [x] Code compiles without warnings or errors.
- [x] All unit and WebMvcTest slice tests pass.
- [x] API Gateway routes include scanner path.
