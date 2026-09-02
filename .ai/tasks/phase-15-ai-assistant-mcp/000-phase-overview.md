# Phase 15 — AI Assistant & MCP / Controlled Tool Calling

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Related ADR:** `.ai/decisions/ADR-014-ai-assistant-tool-orchestration.md`  
**Estimated effort:** ~12–15 focused implementation hours  

---

## 1. Outcome

Deliver a portfolio-worthy AI assistant that can search SeatFlow inventory, reason over user constraints through deterministic tools, recommend seats, and create a reservation only after explicit confirmation.

The goal is agentic domain integration, not generic conversational UI.

## 2. AI Service

Create `ai-service` on default port `8090`:

- Spring AI;
- Eureka client and load-balanced RestClient integrations;
- provider configuration through environment variables;
- no business database access;
- common security/observability modules;
- prompt/tool orchestration isolated from domain services.

The application remains fully usable when AI is disabled or no provider key exists.

## 3. Tool Contracts

Read-only first:

- `searchEvents(query, category, dateRange)`
- `getEvent(eventId)`
- `getEventSessions(eventId)`
- `getAvailableSeats(sessionId, filters)`
- `findBestSeats(sessionId, quantity, maxPrice, preferredSection, strategy)`
- `getReservation(reservationId)`

State-changing:

- `createReservation(sessionId, seatIds, idempotencyKey)` only after explicit user confirmation.

Tools call existing REST APIs; they never query domain databases.

## 4. Deterministic Best-Seat Logic

Do not ask the LLM to invent rankings from raw seat names. Implement a deterministic ranking component using available geometry/pricing/category data. Candidate factors:

- total price <= budget;
- requested quantity;
- adjacency where possible;
- preferred section/category;
- distance/centrality relative to stage when geometry supports it;
- stable tie-breakers.

Return reasons and candidate seat IDs/prices so the assistant can explain the result without fabricating availability.

## 5. Confirmation Boundary

Conversation may reach a `PROPOSED_RESERVATION` state containing exact session/seats/price. The AI must ask the user to confirm that concrete proposal. Only a subsequent explicit confirmation may trigger `createReservation`.

Never auto-complete Stripe payment. Existing checkout remains the payment boundary.

## 6. Identity / Security

- propagate authenticated user's JWT or an approved delegated identity mechanism to tool calls;
- guest AI may be read-only unless a secure guest reservation contract is intentionally supported;
- ADMIN/STAFF tools are not exposed to customer assistant;
- prompt injection cannot grant additional backend roles because services enforce authorization independently;
- redact secrets/payment tokens from model context and logs.

## 7. Frontend

Add assistant drawer/panel with:

- conversation thread;
- suggested starter prompts;
- structured event/session/seat cards;
- clear tool/error/loading states;
- explicit reservation confirmation card;
- link/transition to normal checkout after reservation creation;
- disabled/unavailable state when AI feature flag/provider is off.

## 8. Suggested Atomic Tasks

1. `001-ai-service-scaffold-provider-config-and-security.md`
2. `002-read-only-event-session-tools.md`
3. `003-seat-availability-and-best-seat-ranking.md`
4. `004-assistant-orchestration-and-conversation-contract.md`
5. `005-explicit-confirmation-and-reservation-tool.md`
6. `006-angular-assistant-drawer-and-structured-results.md`
7. `007-ai-tool-security-failure-and-integration-tests.md`

## 9. Definition of Done

- [ ] AI has no direct domain DB access.
- [ ] Read-only search/session/availability tools use live APIs.
- [ ] Seat ranking is deterministic and budget-aware.
- [ ] Reservation tool requires explicit confirmation.
- [ ] 10-seat/15-minute/session/auth rules remain enforced by normal services.
- [ ] Payment remains outside autonomous AI control.
- [ ] SeatFlow works normally with AI disabled.
