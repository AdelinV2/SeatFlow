# ADR-005: Venue Gate Check-In Authorization and Dedicated Staff Scanner Flow

- **Date:** 2026-08-27
- **Author(s):** SeatFlow Architecture Team
- **Driven by Task:** TASK-P06-008 (Refactor Gate Check-In & Introduce ROLE_STAFF)
- **Related ADRs:** ADR-001 (Hybrid Guest Checkout and Guest Ticketing Flow), ADR-002 (Database Indexing and Integrity Standards)
- **Supersedes:** N/A

## 1. Status
`ACCEPTED`

## 2. Context
In earlier architecture drafts, ticket validation at venue entrance gates was exposed under `POST /api/admin/tickets/validate` and protected strictly with `ROLE_ADMIN`.

In real-world live events (theatres, concert halls, festivals, sporting events), ticket validation at turnstiles and entrance gates is performed by operational ground staff (ushers, stewards, volunteers, temporary event staff) using smartphone cameras or tablet devices. 

Granting `ROLE_ADMIN` to operational gate staff directly violates the **Principle of Least Privilege (PoLP)**, exposing administrative capabilities such as:
- Modifying or deleting live events and pricing tiers (`event-service`).
- Altering venue blueprints, sections, and seat layouts (`seat-map-service`).
- Viewing platform financial dashboards, Stripe tax settings, and sales metrics (`payment-service`).
- Viewing or administering customer accounts (`user-service`).

Additionally, clear architectural guidelines are required for QR code payload formatting to ensure dual compatibility:
1. **Attendee Self-Service:** Attendees scanning their printed PDF or digital ticket with standard iOS/Android camera apps should immediately open their responsive digital ticket viewer.
2. **Staff Gate Scanning:** Operational staff using the SeatFlow camera scanner interface (`/scanner`) should instantly parse the ticket code, validate status against the backend, record the validation audit event, and display real-time entry clearance (Green/Red visual feedback with seat location).

---

## 3. Decision

We establish a dedicated **Staff Role & Gate Scanner Architecture**:

```text
┌─────────────────────────────────────────────────────────────┐
│ 1. ROLE HIERARCHY & LEAST PRIVILEGE                         │
│ • ROLE_CUSTOMER  --> Purchases tickets, views profile       │
│ • ROLE_STAFF     --> Scans & validates tickets at gates     │
│ • ROLE_ADMIN     --> Full venue, event & platform admin     │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. DEDICATED SCANNER ENDPOINT                               │
│ POST /api/scanner/tickets/validate                          │
│ Authorized for: ROLE_STAFF, ROLE_ADMIN                      │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. DUAL-PURPOSE QR CODE PAYLOAD                             │
│ Payload: https://seatflow.app/tickets/guest/{ticketCode}    │
│ • Attendee Phone Camera ──► Opens /tickets/guest/{code}     │
│ • Staff App (/scanner)  ──► POST /api/scanner/.../validate  │
└─────────────────────────────────────────────────────────────┘
```

1. **Introduction of `ROLE_STAFF` in `common-security`:**
   - Add `ROLE_STAFF` (`ROLE_STAFF`) and `STAFF` (`STAFF`) constants in `SecurityRoles.java`.
   - Entra ID JWT tokens issued to gate personnel carry `"roles": ["ROLE_STAFF"]`.

2. **Dedicated Scanner Controller (`TicketScannerController`):**
   - Replace `TicketAdminController` with `TicketScannerController` mapped to `/api/scanner/tickets`.
   - Endpoint `POST /api/scanner/tickets/validate` processes `ValidateTicketRequest(ticketCode, scannerDeviceId)` and returns `ValidationResultResponse`.
   - Security rule in `SecurityConfig`:
     ```java
     .requestMatchers("/api/scanner/tickets/**").hasAnyAuthority(SecurityRoles.ROLE_STAFF, SecurityRoles.ROLE_ADMIN)
     ```

3. **API Gateway Route Integration:**
   - Update `api-gateway` route configuration (`application.yaml`) so `/api/scanner/tickets/**` is forwarded to `ticket-service`.

4. **Dual-Compatible QR Code Payload (Deep-Link Standard):**
   - QR code data contains the deep-link URL: `https://seatflow.app/tickets/guest/{ticketCode}`.
   - **Attendee Flow:** Scanning with native iOS/Android camera opens the mobile web viewer (`GuestTicketComponent`).
   - **Staff Flow:** The dedicated Angular scanner route (`/scanner`, protected by `staff.guard.ts`) activates the device camera, detects the QR code, extracts the `ticketCode`, and invokes `POST /api/scanner/tickets/validate`.

5. **Discrete Ticket Entity per Physical Seat:**
   - Every individual seat purchased within a reservation produces a unique `Ticket` entity with its own `ticket_code`, distinct QR code image, and independent entry audit record in `ticket_validations`.
   - In the frontend portal (`/profile/tickets`), users can view an interactive carousel/modal of individual QR codes for each seat and download individual or bundled PDF tickets.

---

## 4. Alternatives Considered

1. **Admin-Only Validation (`ROLE_ADMIN` for all gate workers):**
   - *Pros:* No new role required.
   - *Cons:* Severe security risk; operational staff gain unrestricted administrative powers.
   - *Reason for rejection:* Unacceptable violation of the Principle of Least Privilege.

2. **Public / Unauthenticated Validation Endpoint:**
   - *Pros:* Zero authentication required at gate scanning devices.
   - *Cons:* Vulnerable to denial-of-service and ticket griefing attacks (malicious users scanning someone's QR code from a distance could prematurely invalidate it before entry).
   - *Reason for rejection:* Critical vulnerability.

3. **Raw Code Payload (e.g. `SF-TKT-XXXX` without URL):**
   - *Pros:* Slightly smaller QR image density.
   - *Cons:* Attendees scanning with native phone cameras receive raw unformatted strings with no actionable preview.
   - *Reason for rejection:* Inferior user experience for attendees and guest ticket holders.

---

## 5. Consequences

### Positive:
- Granular, secure access control: Gate staff have access only to ticket validation, with zero access to administrative, event-editing, or financial endpoints.
- Seamless compatibility with commodity mobile devices (smartphones/tablets) without requiring specialized optical scanning hardware.
- High operational speed: Staff receive instant visual feedback (Green: Valid with Seat/Row info; Red: Already Used/Cancelled).
- Comprehensive gate audit log maintained in `ticket_validations` table with device ID and timestamp.

### Negative / Trade-offs:
- Requires assigning the `ROLE_STAFF` claim in Microsoft Entra External ID for gate attendants and ushers.

---

## 6. Implementation Notes
- **Impacted Shared Modules:** `backend/common/common-security` (`SecurityRoles.java`).
- **Impacted Microservices:** `backend/services/ticket-service`, `backend/services/api-gateway`.
- **Impacted Tasks:** `TASK-P06-008` (`008-staff-scanner-controller-and-role-authorization.md`).
- **Documentation Updates:** `04-authentication-security.md`, `06-api-contracts.md`, `07-frontend-specification.md`.
