# 09 — Post-MVP Product & Architecture Evolution (Phases 11–17)

**Status:** AUTHORITATIVE FOR POST-PHASE-10 WORK  
**Applies after:** Phase 10 — DevOps & Observability  
**Product mode:** Portfolio / demo application; Stripe remains Test Mode  

---

## 1. Purpose and Supersession Rule

This document defines the current architecture and implementation order for SeatFlow after Phase 10. The master blueprint predates the final repository phase numbering and contains an older Phase 11–14 sequence. Where that older sequence conflicts with this document, **this document wins for post-MVP sequencing and scope**.

The post-MVP roadmap is deliberately ordered so that domain-shaping changes happen before analytics, AI, content completion, and the final system-wide testing pass.

```text
P11 Advanced Seat Layout
        ↓
P12 Event Sessions / Showings
        ↓
P13 Refunds & Revocation
        ↓
P14 Analytics Read Model
        ↓
P15 AI + Controlled Tools
        ↓
P16 Legal / Support / Public Completion
        ↓
P17 Full System Testing & Final Polish
```

---

## 2. Global Constraints for All New Phases

1. Existing invariants remain mandatory: max 10 seats, 15-minute hold, zero double booking, PostgreSQL source of truth, Transactional Outbox, idempotency, server-side authorization, Eureka/LoadBalancer for synchronous service calls.
2. The application remains a portfolio/demo system. No production card credentials, real settlement, chargebacks, accounting ledger, tax filing, or commercial refund compliance is required.
3. Every new state-changing cross-service workflow uses durable persisted state plus Outbox/Kafka. Do not use Kafka as the source of truth.
4. New frontend affordances must not become authorization or policy enforcement boundaries.
5. Schema changes are additive/backfill-first where practical. Existing demo data should migrate deterministically.
6. The final system-wide test expansion is Phase 17, but each phase must still ship focused unit/integration tests for its own critical behavior.

---

# 3. Phase 11 — Advanced Venue & Seat Map Designer

## 3.1 Goal

Replace the current matrix-oriented venue designer with a flexible but bounded visual layout editor while keeping Seat Map Service authoritative for venue/section/seat business data.

The editor must support real theatre/concert layouts without becoming a general-purpose vector graphics application.

## 3.2 Data Model Evolution

Keep normalized seats and sections. Add visual geometry instead of replacing the domain with one opaque JSON document.

Recommended additions:

- `venue_sections`: `position_x`, `position_y`, `width`, `height`, `rotation_deg`, `z_index`, optional `shape_metadata JSONB`.
- `seats`: add continuous `position_x`, `position_y`; retain `grid_x/grid_y` during migration/compatibility and allow generated layouts to populate both.
- new `venue_layout_elements` table for non-bookable visual elements:
  - `id`, `venue_id`, `type` (`STAGE`, `AISLE`, `LABEL`, `BARRIER`, `DECORATION`), `label`, `geometry JSONB`, `z_index`, timestamps.
- optional `layout_version` on `venues` for optimistic save/conflict detection.

Business identifiers, seat activity and row/seat labels remain normalized columns. JSONB is only for visual geometry that does not participate in booking correctness.

## 3.3 Backend Contracts

Seat Map Service gains editor-oriented endpoints for:

- load complete editable venue layout;
- atomically save section transforms and layout elements;
- bulk create/update/deactivate seats;
- validate layout before publication/use;
- preview read model optimized for customer rendering.

Save operations must be versioned and reject stale editor writes with `409 Conflict`.

## 3.4 Frontend Editor

Admin designer capabilities:

- pan/zoom canvas;
- create/move/resize/rotate sections;
- create stage/aisle/label elements;
- generate rows/seats in bulk;
- row labels and seat numbering rules;
- multi-select and bulk activate/deactivate;
- copy/duplicate section;
- snap-to-grid toggle for productivity, not a storage constraint;
- undo/redo within the current unsaved editor session;
- customer preview mode using the same rendering primitives as seat selection;
- unsaved-change warning and save conflict handling.

Do not implement free-form Bézier drawing, CAD import, 3D seating or arbitrary SVG path editing.

## 3.5 Compatibility

Existing simple grid layouts must render without manual migration. A Flyway migration/backfill maps current `grid_x/grid_y` to initial continuous coordinates. Customer seat selection must continue to consume stable `seatId` values.

---

# 4. Phase 12 — Multiple Event Sessions / Showings

## 4.1 Goal

Separate event identity/content from a concrete scheduled showing. One event can have multiple bookable sessions with independent seat inventory state.

Example:

```text
Hamlet
 ├─ 2026-09-10 19:00
 ├─ 2026-09-11 19:00
 └─ 2026-09-12 17:00
```

## 4.2 Event Service Model

Introduce `event_sessions`:

```text
id UUID
 event_id UUID FK -> events
 starts_at TIMESTAMPTZ
 ends_at TIMESTAMPTZ nullable
 status SCHEDULED | CANCELLED | COMPLETED
 sales_start_at nullable
 sales_end_at nullable
 created_at / updated_at
 version
```

For Phase 12, venue and pricing remain event-level to keep scope controlled. All sessions of one event use the event's venue and pricing tiers. Session-specific venue/pricing may be a later extension, not part of this phase.

Migrate each existing `events.event_date` into exactly one initial session. After all clients/services use sessions, `event_date` becomes deprecated and can be removed in a later migration.

## 4.3 Booking Boundary

`eventSessionId` becomes the inventory partition key.

Reservation Service:

- add non-null `session_id` to reservations after backfill;
- add `session_id` to seat holds;
- unique active-seat constraints must be scoped by `(session_id, seat_id)`, not `(event_id, seat_id)`;
- availability endpoint becomes session-scoped;
- WebSocket topics become `/topic/sessions/{sessionId}/seats`.

The 10-seat and 15-minute rules are unchanged.

## 4.4 Ticket & Payment Effects

Tickets store the session identifier and immutable display snapshot of start date/time needed for rendering. Payment remains reservation-oriented and does not need to understand seat layout internals.

## 4.5 Frontend

- event cards represent the event product;
- Event Detail shows available showings;
- customer selects a session before entering seat selection;
- URL becomes session-explicit where practical, e.g. `/events/:eventId/sessions/:sessionId/seats`;
- admin event editor manages sessions with validation against duplicate/invalid times;
- My Tickets and guest tickets display session date/time;
- calendar groups and filters events by their sessions.

## 4.6 Lifecycle

A session completes independently. An event may be considered completed only when all non-cancelled sessions are completed/past according to the chosen lifecycle rule.

---

# 5. Phase 13 — Refunds & Ticket Cancellation

## 5.1 Core Policy

Customer-initiated refunds are permitted only when:

```text
session.startsAt - serverNow >= 24 hours
```

The check uses authoritative backend time and the selected **event session**, not a frontend clock and not a generic event date.

Additional eligibility:

- requester owns the confirmed reservation/payment, unless ADMIN performs the action;
- payment is in a refundable completed state;
- reservation has not already been refunded/cancelled;
- no duplicate refund workflow is already active.

Initial scope is **full reservation refund only**. Partial/per-ticket refunds are explicitly excluded to keep the workflow deterministic.

## 5.2 Choreographed Refund Workflow

Recommended entry point: Reservation Service.

```text
POST /api/reservations/{reservationId}/refund
        ↓
validate ownership + CONFIRMED + 24h cutoff
        ↓
reservation -> REFUND_PENDING
Outbox: RefundRequested
        ↓ Kafka
Payment Service -> Stripe Test Mode Refund
        ↓
PaymentRefunded | RefundFailed
        ↓
Reservation Service -> REFUNDED or restore CONFIRMED
Ticket Service -> REVOKED on success
Realtime -> released seats become available
Notification -> refund result email
```

Payment Service persists refund identifiers/status and enforces idempotency when calling Stripe. A repeated identical request must not create a second Stripe refund.

## 5.3 States

Recommended additions:

- Reservation: `REFUND_PENDING`, `REFUNDED`.
- Payment: `REFUND_PENDING`, `REFUNDED`, optionally `REFUND_FAILED` if current state machine benefits from explicit failure state.
- Ticket: `REVOKED` or `CANCELLED` (choose one canonical enum; scanner treats it as invalid).

The scanner must clearly report refunded/revoked tickets as invalid.

## 5.4 Seat Release

Only after confirmed refund success do sold seats become available again. Never release inventory merely because a refund was requested.

---

# 6. Phase 14 — Admin Analytics & Operations Dashboard

## 6.1 Goal

Provide portfolio-grade business analytics without violating database-per-service boundaries or turning Grafana into the product UI.

## 6.2 Analytics Service

Introduce `analytics-service` (default `8089`) with its own PostgreSQL database `seatflow_analytics`.

This is an event-driven read model / projection service. It consumes existing and newly added Kafka domain events and builds query-optimized administrative projections. It never participates in the write path and is never required for reservation/payment correctness.

Consumers must be idempotent using processed event IDs or equivalent deduplication.

## 6.3 Suggested Projections

- daily sales/revenue (test-mode values clearly labelled);
- tickets sold/refunded/scanned;
- reservation created/expired/confirmed counts;
- payment success/failure/refund counts;
- event/session occupancy;
- section/category performance where data is available;
- conversion ratio: reservation -> successful payment;
- upcoming events/session operational summary.

Do not store PII unless strictly required for a displayed aggregate. Prefer aggregate dimensions such as event/session/category/date.

## 6.4 Admin APIs/UI

Admin-only REST endpoints expose summaries and time series. Angular admin portal adds KPI cards, charts, date/event/session filters, and optional CSV export of aggregate tables.

Prometheus/Grafana remain infrastructure observability. Product analytics live in the admin portal.

---

# 7. Phase 15 — AI Assistant & MCP / Controlled Tool Calling

## 7.1 Goal

Add an AI assistant that demonstrates controlled agentic interaction with SeatFlow rather than a generic chatbot.

Introduce `ai-service` (default `8090`) using Spring AI. The provider integration must be configurable; domain tool contracts must remain provider-neutral.

## 7.2 Hard Security Boundary

AI Service has **no direct database access** to Event, Reservation, Seat Map, Payment or Ticket databases.

Tools call existing service APIs through Eureka/LoadBalancer and propagate user identity/correlation context. Tool execution is subject to the same server-side authorization and business invariants as normal UI requests.

## 7.3 Tool Set

Initial read-only tools:

- `searchEvents`
- `getEvent`
- `getEventSessions`
- `getAvailableSeats`
- `findBestSeats`
- `getReservation`

State-changing tool:

- `createReservation`

`createReservation` is enabled only after explicit user confirmation. Payment is never autonomously completed by the AI.

## 7.4 Seat Ranking

`findBestSeats` is deterministic application logic, not an LLM guess. Inputs may include budget, quantity, preferred section/category and closeness preference. The tool returns ranked candidate sets based on authoritative availability/pricing.

## 7.5 Frontend

Add a discoverable assistant drawer/panel. Show tool progress at a user-friendly level and render structured seat/session suggestions. Confirmation before reservation must be explicit and unambiguous.

The base SeatFlow application must still start and function when AI credentials are absent; AI UI may be disabled via environment configuration.

---

# 8. Phase 16 — Public Site Completion, Legal & Support

## 8.1 Goal

Make every public footer/navigation destination intentional and useful. The current footer already advertises legal/support/status routes; this phase implements them rather than allowing wildcard redirection.

## 8.2 Required Routes

At minimum:

- `/legal/terms`
- `/legal/privacy`
- `/legal/tax`
- `/legal/refunds`
- `/legal/cookies`
- `/legal/security`
- `/support/faq`
- `/support/contact`
- `/status`
- `/api-docs`
- real not-found page (`/404` or wildcard component)

## 8.3 Demo Disclosure

Legal/payment-related pages must clearly state that SeatFlow is a portfolio/demo application using Stripe Test Mode and does not process real commercial transactions.

Refund documentation must match the implemented 24-hour policy exactly.

Do not fabricate a legal entity, postal address, DPO or commercial guarantees. Content should be transparent demo documentation, not pretend production legal advice.

## 8.4 Cookie Behavior

Do not build a consent platform unless the application actually introduces non-essential cookies/tracking. If only essential auth/session storage exists, explain it accurately. Cookie preferences UI must correspond to real configurable categories.

---

# 9. Phase 17 — Full Testing, Quality Gates & Final Polish

## 9.1 Goal

Freeze feature development and validate the final architecture end-to-end.

## 9.2 Backend Test Matrix

Mandatory focus areas:

- Testcontainers for PostgreSQL, Kafka and Redis paths;
- reservation concurrency and zero double-booking per session;
- 15-minute expiration;
- event session migration/lifecycle;
- refund cutoff at exactly/before/after 24 hours;
- refund idempotency and failure recovery;
- ticket revocation/scanner rejection;
- outbox idempotency and duplicate Kafka delivery;
- analytics projection deduplication;
- auth role matrix USER/STAFF/ADMIN;
- AI tool authorization and explicit-confirmation boundary.

## 9.3 E2E Scenarios

Playwright should cover a small number of high-value flows:

1. browse -> choose session -> choose seats -> reserve -> Stripe Test payment -> ticket;
2. authenticated My Tickets flow;
3. eligible refund -> ticket revoked -> seat available again -> scanner rejects revoked ticket;
4. ineligible refund inside 24h;
5. admin creates venue/layout/event/sessions and views analytics;
6. AI read-only search and confirmed reservation flow when AI test configuration is enabled.

## 9.4 Quality Gates

- backend `mvn verify`;
- frontend lint/test/build;
- Playwright smoke/E2E suite;
- static analysis and dependency scanning;
- Docker/Compose validation;
- final GCP deployment smoke tests;
- README architecture/feature screenshots and diagrams synchronized with reality.

No new major feature is added during this phase unless required to fix a defect discovered by the test pass.

---

## 10. Cross-Phase Migration Order

The ordering is intentionally non-commutative:

- P11 first because seat layout storage/rendering is foundational and difficult to retrofit after more UI is built.
- P12 before refunds because refund eligibility must refer to the concrete session start time.
- P13 before analytics so refund events/states are part of the analytics model from day one.
- P14 before AI so the admin domain is stable while AI remains focused on customer discovery/reservation.
- P15 before public-page completion so final navigation/help content can describe the AI feature accurately if enabled.
- P16 before P17 so the final test pass includes every advertised public route.

---

## 11. Explicit Non-Goals for Phases 11–17

- real-money production Stripe credentials;
- partial ticket refunds;
- exchanges/rescheduling tickets between sessions;
- resale marketplace;
- loyalty/coupons/social features;
- mobile native apps;
- Kubernetes/GKE migration;
- 3D venue editor or general-purpose CAD tooling;
- AI-autonomous payment or authorization bypass;
- production legal/compliance certification.
