# 02 — Microservices Detailed Specification

This document details the responsibilities, dependencies, internal architecture, and integration boundaries for each microservice in SeatFlow.

---

## 1. Service Discovery: `eureka-server` (Port 8761)
- **Tech:** Spring Cloud Netflix Eureka Server (Spring Cloud 2025.1 Oakwood).
- **Responsibility:** Central registry for service discovery and dynamic client-side load balancing.
- **Config:** `eureka.client.register-with-eureka: false`, `eureka.client.fetch-registry: false`.

---

## 2. API Gateway: `api-gateway` (Port 8080)
- **Tech:** Spring Cloud Gateway (Reactive / WebFlux).
- **Responsibilities:**
  - Single public entry point for the Angular frontend.
  - Dynamic routing to downstream services via Eureka (`lb://SERVICE-NAME`).
  - CORS configuration for frontend origin.
  - JWT token validation and forwarding via Token Relay.
  - Request rate limiting via Redis (`RedisRateLimiter`).
- **Must Not:** Contain any domain business logic or database access.

---

## 3. Identity & User Service: `user-service` (Port 8081)
- **Database:** `seatflow_user` (PostgreSQL).
- **Responsibilities:**
  - JIT (Just-In-Time) user profile synchronization upon first authenticated request.
  - Customer profile retrieval and updates (phone, preferences).
  - Admin view of registered customers.
  - Historical guest order linking: When a user registers with an email address previously used for guest checkouts, link historical tickets to the registered profile via `UserRegisteredEvent`.
- **Dependencies:** `common-domain`, `common-events`, `common-observability`, `common-security`, Kafka.

---

## 4. Seat Map & Venue Service: `seat-map-service` (Port 8082)
- **Database:** `seatflow_seatmap` (PostgreSQL).
- **Responsibilities:**
  - Management of physical venues (name, location, total capacity).
  - Management of venue sections (e.g. VIP, Balcony, Orchestra).
  - Grid coordinate layout of rows and seat numbers (`grid_x`, `grid_y`, `row_label`, `seat_number`).
  - Venue seat layout retrieval for the interactive seat map UI.
  - Publishing `VenueCreated` and `VenueSectionCreated` domain events via Transactional Outbox.
- **Dependencies:** `common-domain`, `common-events`, `common-observability`, `common-security`, Kafka.

---

## 5. Event Catalog Service: `event-service` (Port 8083)
- **Database:** `seatflow_event` (PostgreSQL).
- **Responsibilities:**
  - Event management: Title, description, banner URL, category, dates, status (`DRAFT`, `PUBLISHED`, `CANCELLED`, `COMPLETED`).
  - Associating an event with a venue layout from `seat-map-service`.
  - Seat category pricing tiers (e.g., VIP = \$150, General = \$50, Student = \$30).
  - Public event catalog queries with pagination, search, and date filters.
- **Dependencies:** `common-domain`, `common-events`, `common-observability`, `common-security`.

---

## 6. Reservation Service: `reservation-service` (Port 8084)
- **Database:** `seatflow_reservation` (PostgreSQL).
- **Responsibilities:**
  - Temporary seat hold creation (15 minutes).
  - **Hybrid Guest & Authenticated Holds:** Supports authenticated customers (resolving `userId` from token) and guest customers (accepting `customerEmail` and optional `customerName` in payload).
  - Enforce maximum 10 seats per reservation limit.
  - **Authoritative Server-Side Pricing:** The client only sends `eventId` and `seatIds`. `reservation-service` queries `event-service` via internal REST client to fetch official pricing tiers and calculates `total_amount` authoritatively on the server side (never trusts client prices).
  - Concurrency control: Zero double-booking guarantee via DB unique constraints and pessimistic/optimistic locking.
  - Reservation state machine: `PENDING` → `CONFIRMED` | `CANCELLED` | `EXPIRED`.
  - Transactional Outbox Pattern for publishing `ReservationHeld`, `ReservationExpired`, `ReservationCancelled`.
  - **Multi-Instance Expiration Sweeper:** Background sweeper job (`@Scheduled` + `SELECT ... FOR UPDATE SKIP LOCKED`) releasing expired holds without deadlock or cluster contention across instances.
- **Dependencies:** `common-domain`, `common-events`, `common-observability`, `common-security`, Kafka.

---

## 7. Payment Service: `payment-service` (Port 8085)
- **Database:** `seatflow_payment` (PostgreSQL).
- **Responsibilities:**
  - Stripe integration (Payment Intent creation in Test Mode for both authenticated and guest reservations).
  - **Cryptographic Webhook Verification:** Verifies incoming `Stripe-Signature` headers against the configured endpoint secret.
  - **Webhook Idempotency:** Validates existing payment status (`if (payment.getStatus() == PaymentStatus.SUCCESS) return;`) to safely ignore duplicate webhook events from Stripe without producing duplicate outbox events.
  - Publishing `PaymentCompleted` or `PaymentFailed` domain events via Transactional Outbox.
  - Payment state machine: `INITIATED` → `SUCCESS` | `FAILED` | `REFUNDED`.
- **Dependencies:** `common-domain`, `common-events`, `common-observability`, `common-security`, Stripe SDK.

---

## 8. Ticket Service: `ticket-service` (Port 8086)
- **Database:** `seatflow_ticket` (PostgreSQL).
- **Responsibilities:**
  - Listens to `PaymentCompleted` Kafka events.
  - Generates secure digital tickets with cryptographic verification tokens (supporting both registered users and guest purchasers).
  - Generates QR codes using **ZXing** containing signed ticket payload.
  - Renders downloadable PDF tickets.
  - Publishing `TicketIssued` domain event via Outbox.
  - Ticket query endpoints for customer profile ("My Tickets"), secure guest access (`GET /api/tickets/guest/{ticketCode}`), and admin check-in.
- **Dependencies:** `common-domain`, `common-events`, `common-observability`, `common-security`, ZXing, OpenPDF.

---

## 9. Realtime Service: `realtime-service` (Port 8087)
- **Database:** None (Redis for state coordination).
- **Responsibilities:**
  - WebSocket server using **STOMP** over SockJS.
  - Listens to Kafka topic `seatflow.seat.status.events` (events published by Reservation & Payment services).
  - Broadcasts seat status updates (`AVAILABLE`, `HELD`, `SOLD`) in real time to connected Angular clients subscribing to `/topic/events/{eventId}/seats`.
- **Dependencies:** `common-domain`, `common-events`, `common-observability`, `common-security`, Spring WebSocket STOMP.

---

## 10. Notification Service: `notification-service` (Port 8088)
- **Database:** `seatflow_notification` (PostgreSQL).
- **Responsibilities:**
  - Listens to Kafka events (`TicketIssued`, `ReservationHeld`, `PaymentFailed`).
  - Asynchronously generates HTML email confirmations using Thymeleaf templates with attached PDF ticket and secure guest access link.
  - Dispatches emails via SMTP / SendGrid adapter directly to `customerEmail`.
  - Tracks delivery logs and retry attempts in `notification_logs` table.
- **Dependencies:** `common-domain`, `common-events`, `common-observability`, JavaMailSender / SendGrid.
