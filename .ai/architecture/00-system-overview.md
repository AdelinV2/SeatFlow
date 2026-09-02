# 00 — System Overview & Architecture Topology

**Project:** SeatFlow  
**Type:** Portfolio-Grade, Production-Oriented Event Ticketing & Real-Time Seat Reservation Platform  
**Target Stack:** Java 21 (LTS), Spring Boot 4.1.x (Spring Framework 7), Angular 22, PostgreSQL, Redis, Kafka  
**Service Discovery:** Netflix Eureka Server + Spring Cloud Eureka Clients (Spring Cloud 2025.1.x Oakwood)  
**Authentication:** Supabase Auth (OIDC / JWT, Google Federation, Email/Password - ADR-006)  

---

## 1. Product Vision

SeatFlow is an online event ticketing and seat reservation platform inspired by modern theatre/concert ticketing systems.

### 1.1 Customer Capabilities
1. Browse upcoming events with search, categories, and date filters.
2. View event details (dates, description, venue layout, pricing tiers).
3. Select up to **10 available seats** concurrently on an interactive seat map.
4. Place a temporary **15-minute hold** on the selected seats.
5. Complete checkout seamlessly as a **Registered User** or as a **Guest** (providing only an email address and name, without creating an account).
6. Complete payment via Stripe (using Payment Intents with Stripe Tax automatic calculation, tax-inclusive pricing, and secure webhooks).
7. Receive email purchase confirmation and digital tickets with QR codes (ZXing) and secure access link.
8. View historical orders and upcoming tickets in the user profile area (for registered users) or via secure link (for guests).
9. Receive real-time seat availability updates via WebSockets without page reloads.

### 1.2 Administrator Capabilities
1. Create and manage events, dates, and descriptions.
2. Configure venues, sections, rows, and seat layouts.
3. Configure seat categories and dynamic pricing tiers.
4. Monitor real-time reservations, ticket issuance, and sales metrics.

### 1.3 Core Engineering Principles
- **Small Product, Deep Engineering:** Maintain a tight business scope while demonstrating enterprise-grade Java engineering, distributed transactions, concurrency control, messaging, observability, and testing.
- **Correctness Before Optimization:** Zero double-booking guarantee, transactional hold expiration, and idempotent payments even during partial service failures.
- **Database Ownership:** Strict Database-per-Service. Services never query another service's database directly.
- **PostgreSQL is Source of Truth:** Redis is a temporary cache/locking layer; database state is always authoritative.

---

## 2. High-Level Architecture Topology

```text
                                      ┌─────────────────────────────┐
                                      │        Angular SPA          │
                                      │ REST + WebSocket / OIDC     │
                                      └──────────────┬──────────────┘
                                                     │
                                                     │ HTTPS / WS
                                                     ▼
                                      ┌─────────────────────────────┐
                                      │         API Gateway         │
                                      │   Port 8080 / CORS / Auth   │
                                      └───────┬───────────┬─────────┘
                                              │           │
                               synchronous REST│           │WebSocket
                                              │           ▼
                                              │   ┌──────────────────┐
                                              │   │ Realtime Service │ (8087)
                                              │   └────────┬─────────┘
                                              │            │
                                              │            │ consumes events
                                              ▼            │
               ┌──────────────────────────────────────────────────────────────┐
               │                         Eureka Server                        │ (8761)
               │                 Service Registry / Discovery                 │
               └───────────────────────┬──────────────────────────────────────┘
                                       │
                      service instances register / discover
                                       │
       ┌───────────────────────────────┼──────────────────────────────────────────┐
       │                               │                                          │
       ▼                               ▼                                          ▼
┌──────────────┐                ┌──────────────┐                           ┌──────────────┐
│ User Service │ (8081)         │ Event Service│ (8083)                    │ Seat Map     │ (8082)
│              │                │              │                           │ Service      │
└──────┬───────┘                └──────┬───────┘                           └──────┬───────┘
       │                               │                                          │
       │                               │ REST for synchronous queries             │
       │                               │                                          │
       ▼                               ▼                                          ▼
 PostgreSQL (5432)               PostgreSQL (5432)                          PostgreSQL (5432)

       ┌───────────────────────────────┼──────────────────────────────────────────┐
       │                               │                                          │
       ▼                               ▼                                          ▼
┌───────────────┐              ┌───────────────┐                           ┌──────────────┐
│ Reservation   │ (8084)       │ Payment       │ (8085)                    │ Ticket       │ (8086)
│ Service       │              │ Service       │                           │ Service      │
└───────┬───────┘              └───────┬───────┘                           └──────┬───────┘
        │                              │                                          │
        │                              │ Stripe API + webhook                     │
        ▼                              ▼                                          ▼
 PostgreSQL (5432)                  PostgreSQL (5432)                          PostgreSQL (5432)
        │
        │ Transactional Outbox
        └──────────────────────────────┐
                                       │
                                       ▼
                               ┌──────────────────┐
                               │      Kafka       │ (9092)
                               │ Asynchronous Bus │
                               └────────┬─────────┘
                                        │
                  ┌─────────────────────┼───────────────────────┐
                  │                     │                       │
                  ▼                     ▼                       ▼
         Notification Service (8088)  Realtime Service (8087)  Downstream Consumers
                  │                     │
                  ▼                     └───────► WebSocket Clients
           External Email API
```

---

## 3. Microservices & Network Ports Reference

| Service | Port | Database | Primary Responsibility |
|---|---|---|---|
| **Eureka Server** | `8761` | None | Service registry and discovery |
| **API Gateway** | `8080` | None | Single entry point, routing, CORS, JWT forwarding |
| **User Service** | `8081` | `seatflow_user` | User profiles, identity sync from Supabase Auth |
| **Seat Map Service** | `8082` | `seatflow_seatmap` | Venue layouts, sections, rows, seat configurations |
| **Event Service** | `8083` | `seatflow_event` | Event catalog, dates, descriptions, category pricing |
| **Reservation Service** | `8084` | `seatflow_reservation` | 15-minute seat holds, concurrency locks, hold expiration sweeper |
| **Payment Service** | `8085` | `seatflow_payment` | Stripe payment intents, Stripe Tax (tax-inclusive), webhooks, payment state |
| **Ticket Service** | `8086` | `seatflow_ticket` | Ticket issuance, ZXing QR codes, PDF generation |
| **Realtime Service** | `8087` | None (Redis) | WebSocket STOMP server, seat status live broadcasts |
| **Notification Service**| `8088`| `seatflow_notification`| Async email notifications via Kafka events |

---

## 4. Communication Rules

### 4.1 Synchronous Communication (REST / HTTP)
- Used when the caller requires an immediate response (e.g. browsing events, rendering a seat map, requesting a hold, checking payment status).
- Service-to-service synchronous calls resolve instances dynamically via **Eureka** using Spring Cloud LoadBalancer (`@LoadBalanced RestClient.Builder` with logical service URLs, e.g. `http://<service-name>`).
- All synchronous REST clients must configure explicit connect/read timeouts (`SimpleClientHttpRequestFactory`), forward `X-Correlation-Id` via `CorrelationContext`, and protect remote invocations with Resilience4j circuit breakers.
- Services declaring `@LoadBalanced` builders must also define a `@Primary` plain `RestClient.Builder` bean to preserve Eureka client's internal registration mechanism.

### 4.2 Asynchronous Communication (Kafka)
- Used for all decoupled domain events and cross-service state transitions:
  - Event created / published / cancelled / completed.
  - Reservation held / expired / cancelled.
  - Payment completed / failed.
  - Ticket created.
  - Email notification trigger.
  - Real-time seat status update broadcast.
- Critical state changes publish via the **Transactional Outbox Pattern** to prevent dual-write discrepancies.

---

## 5. Post-MVP Architecture Evolution (Phases 11–17)

The original master blueprint contains an older post-MVP sequence whose phase numbers no longer match the implemented repository. For all work **after Phase 10**, the authoritative sequencing and architecture extension is now:

- `.ai/architecture/09-post-mvp-evolution.md`

The approved sequence is:

1. **Phase 11 — Advanced Venue & Seat Map Designer**
2. **Phase 12 — Multiple Event Sessions / Showings**
3. **Phase 13 — Refunds & Ticket Cancellation**
4. **Phase 14 — Admin Analytics & Operations Dashboard**
5. **Phase 15 — AI Assistant & MCP / Tool Calling**
6. **Phase 16 — Public Site Completion, Legal & Support**
7. **Phase 17 — Full Testing, Quality Gates & Final Polish**

These phases preserve the existing portfolio/demo deployment model and Stripe Test Mode. They do **not** turn SeatFlow into a real-money commercial ticketing system.

### 5.1 Planned Service Extensions

Two optional services are introduced only when their phases are implemented:

| Planned Service | Default Port | Database | Purpose |
|---|---:|---|---|
| **Analytics Service** | `8089` | `seatflow_analytics` | Event-driven business read model for admin KPIs and charts |
| **AI Service** | `8090` | None required | Spring AI orchestration and controlled domain tool calling; no direct DB access |

The base application must remain functional when the AI provider/API key is absent. The Analytics Service is administrative and must never become part of reservation correctness or payment execution.

### 5.2 New Cross-Cutting Business Invariants

- **Bookable inventory is session-scoped:** after Phase 12, availability and reservations are bound to `eventSessionId`, not only `eventId`.
- **Refund cutoff:** after Phase 13, a confirmed purchase is eligible for a customer-initiated refund only when the corresponding event session starts at least **24 hours** after the authoritative server time at the moment the refund request is accepted.
- **Refund policy is server-side:** frontend visibility of the refund button is convenience only; Reservation Service enforces ownership, state, and the 24-hour cutoff.
- **Refunded tickets are invalid:** successful refund causes ticket revocation and released seats become available again when the session is still bookable.
- **AI never bypasses domain rules:** AI tools invoke existing authenticated APIs; they do not query databases directly and cannot bypass the 10-seat limit, 15-minute hold, authorization, session availability, or refund policy.
- **Testing is intentionally final:** Phase 17 adds the full cross-service/E2E quality gate after the new domain model and post-MVP features are stable. Feature phases still require focused tests for the code they introduce.
