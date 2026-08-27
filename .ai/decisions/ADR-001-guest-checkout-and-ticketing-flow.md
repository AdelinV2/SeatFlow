# ADR-001: Hybrid Guest Checkout and Guest Ticketing Flow

- **Date:** 2026-08-24
- **Author(s):** SeatFlow Architecture Team
- **Driven by Task:** Architectural Evolution / Guest Checkout Support
- **Supersedes:** N/A

## 1. Status
`ACCEPTED`

## 2. Context
In the original architecture specification, creating a seat reservation (`POST /api/reservations`), initiating payment (`POST /api/payments/intent`), and accessing tickets required mandatory user authentication via Microsoft Entra External ID (OIDC / JWT) with the `ROLE_CUSTOMER` authority.

However, event ticketing platforms often experience significant drop-off rates when forced account registration/login is imposed at checkout. To maximize conversion rates and provide a frictionless purchasing experience, users should be able to select seats, reserve them, and complete ticket purchases using only an email address and attendee name, without creating an account or logging in (Guest Checkout). At the same time, registered users must retain the ability to check out while authenticated and view all historical tickets in their profile.

## 3. Decision
We adopt a **Hybrid Guest Checkout** model across all relevant microservices and contracts:

1. **Nullable `userId` with Mandatory `customerEmail`:**
   - In `reservations`, `payments`, and `tickets` database schemas, `user_id` becomes `UUID NULL`.
   - A new required field `customer_email VARCHAR(255) NOT NULL` and optional `customer_name VARCHAR(255)` are introduced.
   - For authenticated requests, `userId` and `customerEmail` are automatically resolved from the verified JWT claims (`sub`, `email`, `name`).
   - For unauthenticated requests (Guest), `userId` is `null`, and `customerEmail` / `customerName` are provided directly in the request payload.

2. **Public / Optional Authentication Endpoints:**
   - `POST /api/reservations` and `POST /api/payments/intent` are configured as publicly accessible endpoints in Spring Security and API Gateway.
   - If an `Authorization: Bearer <JWT>` header is present, the microservices extract and attach the authenticated `userId`. Otherwise, the transaction is processed as a guest reservation.

3. **Event-Driven Outbox Payloads:**
   - Kafka domain events (`ReservationHeldEvent`, `PaymentCompletedEvent`, `TicketIssuedEvent`) are updated to carry `customerEmail`, `customerName`, and an optional `userId`.

4. **Digital Ticket Delivery & Guest Access:**
   - `notification-service` dispatches an HTML confirmation email containing the rendered PDF ticket(s) and a secure, time-stamped / cryptographically signed guest access link (`/tickets/guest/{ticketCode}`).
   - **Multi-Ticket Transactions:** If a guest purchase includes multiple seats, the guest ticket viewer (`/tickets/guest/{ticketCode}`) provides a tabbed multi-ticket switcher ("Biletul 1 din N"), distinct QR codes for each seat, individual/bundle PDF downloads, and an account linking prompt to associate all tickets to an account.
   - Registered users can additionally access their tickets via `GET /api/tickets/my-tickets` in the Angular portal.

5. **Bot Protection & Abuse Prevention:**
   - Because `POST /api/reservations` is publicly reachable, the API Gateway enforces IP-based rate limiting via Redis (`RedisRateLimiter`) to prevent automated seat-locking denial-of-service attacks.
   - The 15-minute hold expiration invariant and the 10-seat limit per reservation remain strictly enforced on the server side.

6. **Automatic Email-Based Claiming on Account Registration (UserRegisteredEvent):**
   - When a guest user subsequently registers an account with Microsoft Entra External ID and logs in for the first time, `user-service` creates their profile and publishes a `UserRegisteredEvent` (containing `userId` and `email`) to the `seatflow.user.events` topic via the Transactional Outbox.
   - Downstream services (`ticket-service` and `reservation-service`) consume `UserRegisteredEvent` and automatically associate all historical guest records with the newly registered user:
     ```sql
     UPDATE tickets 
     SET user_id = :userId 
     WHERE customer_email = :email AND user_id IS NULL;
     ```
   - This ensures full continuity: all past tickets purchased as a guest immediately appear in the user's `/profile/tickets` ("My Tickets") dashboard upon account creation without requiring any manual claim flow.

## 4. Alternatives Considered
1. **Mandatory Authentication (Original Design):**
   - *Pros:* Simpler schema (`user_id NOT NULL` everywhere), easier ticket access control.
   - *Cons:* High checkout friction, cart abandonment, poor user experience for one-off event attendees.
   - *Reason for rejection:* Unfavorable for high-conversion event ticketing.

2. **Shadow Anonymous Accounts (Backend JIT User Creation):**
   - *Pros:* Keeps `user_id NOT NULL` in downstream tables.
   - *Cons:* Polutes `user-service` with unverified dummy accounts; complicates identity lifecycle and GDPR compliance.
   - *Reason for rejection:* Nullable `userId` with explicit `customerEmail` is cleaner and domain-accurate.

3. **Social Login Only (No Guest Checkout):**
   - *Pros:* 1-click login without typing passwords.
   - *Cons:* Still requires OAuth consent screen redirection during the critical 15-minute seat hold window.
   - *Reason for rejection:* Guest checkout provides a faster, zero-redirect experience.

## 5. Consequences
### Positive:
- Frictionless seat reservation and checkout flow for guests.
- Higher conversion rates and modern e-commerce checkout standard.
- Clean separation between authenticated customer features (saved payment methods, "My Tickets" profile dashboard) and guest checkout.
- Automated email delivery of tickets with QR code ensures guests always receive their tickets immediately.

### Negative / Trade-offs:
- Public reservation endpoint introduces potential bot abuse surface.
  - *Mitigation:* Aggressive IP-based rate limiting in API Gateway + 10-seat cap + 15-min auto-release.
- Guest ticket retrieval needs secure token/hash verification so unauthorized parties cannot enumerate tickets.
  - *Mitigation:* Ticket lookup by unique cryptographically secure `ticket_code` and signed email link.

## 6. Implementation Notes
- **Impacted Microservices:** `reservation-service`, `payment-service`, `ticket-service`, `notification-service`, `api-gateway`, `user-service`.
- **Impacted Shared Modules:** `common-events` (event payload DTOs).
- **Documentation Updates:** `00-system-overview.md`, `02-microservices-spec.md`, `03-database-models.md`, `04-authentication-security.md`, `05-messaging-and-outbox.md`, `06-api-contracts.md`, `07-frontend-specification.md`, `SeatFlow-Architecture-and-Implementation-Spec.md`.
