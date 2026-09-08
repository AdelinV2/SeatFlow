# Phase 15 — AI Assistant & Controlled Tool Calling

**Status:** `PLANNED / TASKS READY`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Related ADR:** `.ai/decisions/ADR-014-ai-assistant-tool-orchestration.md`  
**Primary provider for this phase:** Groq Cloud Free Plan through its OpenAI-compatible API  
**Spring AI baseline:** Spring AI `2.0.1` (compatible with Spring Boot `4.1.x`)  
**Default Groq model:** `openai/gpt-oss-20b`  
**Estimated effort:** ~15–22 focused implementation hours, excluding provider-account setup and final manual demo verification  

---

## 1. Outcome

Deliver a portfolio-worthy AI assistant that can understand natural-language event/seat requests, use controlled SeatFlow tools, recommend authoritative seat candidates, and create a 15-minute reservation only after explicit user confirmation.

The goal is **agentic domain integration**, not a generic chatbot and not autonomous purchasing.

Phase 15 must preserve every existing SeatFlow invariant:

- session-scoped inventory;
- max 10 seats per reservation;
- 15-minute hold;
- zero double booking;
- PostgreSQL/domain services remain authoritative;
- existing server-side authorization remains authoritative;
- AI never receives direct domain database access;
- payment remains a normal human-controlled Stripe checkout step.

---

## 2. Provider Decision — Groq Without Architecture Lock-In

SeatFlow uses **Groq** as the initial deployed model provider because it provides a free API tier suitable for a portfolio/demo workload and supports function/tool calling.

Implementation rules:

- integrate Groq through Spring AI's OpenAI-compatible client;
- default base URL: `https://api.groq.com/openai/v1`;
- default model: `openai/gpt-oss-20b` because it is a Groq production model with tool-use support;
- model ID must be configurable through `GROQ_MODEL`; never scatter the model string through application code;
- API key comes only from `GROQ_API_KEY` or the deployment secret mechanism;
- never commit a real key;
- never expose the key to Angular;
- never assume today's Groq free-tier limits are permanent business invariants;
- quota exhaustion, provider outage, model deprecation, and invalid credentials must degrade only the AI feature, not SeatFlow's booking platform.

The domain tool contracts remain provider-neutral. Changing from Groq later must not require changes to Event/Seat Map/Reservation services or frontend domain contracts.

### 2.1 Required user configuration checkpoint

The only mandatory manual provider setup is introduced in **TASK-P15-001**:

1. create/sign in to a Groq Cloud account;
2. create a Groq API key;
3. put the key in the local secret/env configuration documented by the task;
4. when the AI feature is deployed publicly, add the same secret to the production deployment environment without committing it to Git.

Tasks P15-002 through P15-007 must be implementable and testable with mocks/stubs when a live Groq key is unavailable. A live key is required only for the final provider smoke test and actual interactive demo.

---

## 3. AI Service

Create `ai-service` on default port `8090`:

- Spring Boot / Spring AI;
- Eureka client and load-balanced `RestClient` integrations;
- Groq provider configuration through environment variables;
- zero JPA/JDBC/Flyway/domain-database dependencies;
- shared security and observability modules;
- prompt/orchestration logic isolated from domain services;
- provider health/feature state that can report disabled, misconfigured, rate-limited, or unavailable without breaking core APIs.

The entire SeatFlow application must start and remain usable when AI is disabled or no valid provider key exists.

---

## 4. Controlled Tool Model

Phase 15 uses Spring AI application-owned tool calling. A separate remote MCP server is **not required** for the Phase 15 Definition of Done.

The term "MCP / controlled tool calling" in older planning material means tools are explicit, typed, least-privilege domain capabilities rather than unrestricted model access. If external MCP exposure is added later, it must wrap the same tool contracts and security boundaries rather than bypass them.

### 4.1 Read-only tools

- `searchEvents(query, category, dateRange)`
- `getEvent(eventId)`
- `getEventSessions(eventId)`
- `getAvailableSeats(sessionId, filters)`
- `findBestSeats(sessionId, quantity, maxTotalPriceMinor, currency, preferredSection, preferredCategory, strategy)`
- `getReservation(reservationId)`

### 4.2 State-changing tool

- `createReservation(sessionId, seatIds, idempotencyKey)`

`createReservation` is inaccessible to the ordinary automatic tool set until the server has a validated, unexpired `PROPOSED_RESERVATION` and receives explicit confirmation for exactly that proposal.

Do not interpret a vague conversational phrase as sufficient authorization to change state. The confirmation endpoint/action is the authoritative boundary.

---

## 5. Deterministic Best-Seat Logic

The LLM must never invent availability, seat IDs, prices, adjacency, or rankings.

`findBestSeats` is deterministic application code over live SeatFlow API data. It must:

1. read session availability from Reservation Service;
2. read seat geometry/section/row/seat-number data from the existing event/seat-map read APIs;
3. read authoritative pricing from the existing event/session pricing contract;
4. intersect candidates by stable `seatId`;
5. reject inactive/non-available seats;
6. enforce quantity `1..10`;
7. enforce requested currency and total budget in integral minor units;
8. prefer contiguous seats in the same section and row, where contiguity means consecutive integer `seatNumber` values;
9. use deterministic geometry/section/category scoring only after hard constraints pass;
10. use stable tie breakers ending with lexicographic UUID/string seat IDs so repeated identical snapshots produce identical output.

When the requested quantity cannot be seated contiguously, the tool may return clearly marked non-contiguous alternatives; it must never label them adjacent.

---

## 6. Conversation and Confirmation State

Conversation state is application-owned and bounded. The model does not own authorization state.

Required states:

```text
IDLE
  -> DISCOVERING
  -> PROPOSAL_READY
  -> CONFIRMATION_REQUIRED
  -> RESERVATION_CREATED

Any state -> ERROR_RECOVERABLE
Any state -> EXPIRED/RESET when proposal context becomes stale
```

A proposal stores only the minimum structured data needed for confirmation:

- authenticated user/session correlation;
- event/session IDs;
- exact seat IDs;
- display labels;
- price/currency snapshot;
- proposal creation/expiry time;
- server-generated proposal token/id;
- deterministic idempotency key or server-side idempotency seed.

Never store provider secrets, payment tokens, raw JWTs, or unrestricted prompt history as durable business data.

Before reservation creation, availability and price must be revalidated against live services. A proposal is not a lock.

---

## 7. Frontend

Add a discoverable assistant drawer/panel with:

- conversation thread;
- suggested starter prompts;
- structured event/session/seat cards;
- clear loading/tool/error states;
- disabled/unavailable state when AI feature flag/provider is off;
- explicit reservation confirmation card showing exact session, seats, and price;
- clear handling for stale proposal / seat no longer available / provider quota exhausted;
- successful reservation state with hold-expiry countdown sourced from Reservation Service;
- link/transition to normal checkout after reservation creation.

Do not expose raw tool JSON, chain-of-thought/reasoning, provider credentials, internal stack traces, or security-sensitive prompts in the UI.

---

## 8. Atomic Task Order

1. `001-ai-service-scaffold-groq-config-and-security.md`
2. `002-read-only-event-and-session-tools.md`
3. `003-seat-availability-and-deterministic-best-seat-ranking.md`
4. `004-assistant-orchestration-and-conversation-contract.md`
5. `005-explicit-confirmation-and-reservation-tool.md`
6. `006-angular-assistant-drawer-and-structured-results.md`
7. `007-ai-security-provider-failure-and-integration-tests.md`

Execution is sequential unless a task explicitly says a frontend/test portion may start after its API contract is frozen.

---

## 9. Definition of Done

- [ ] Groq configuration is externalized and no secret exists in Git or Angular bundles.
- [ ] SeatFlow starts normally with AI disabled and/or without a Groq key.
- [ ] AI has no direct domain DB access.
- [ ] Read-only search/session/availability tools use live authenticated APIs.
- [ ] Seat ranking is deterministic, budget-aware, currency-safe, quantity-safe, and adjacency-honest.
- [ ] LLM cannot directly invoke state-changing reservation operations from an ordinary chat turn.
- [ ] Reservation creation requires explicit confirmation of an exact server-side proposal and revalidation.
- [ ] 10-seat/15-minute/session/auth rules remain enforced by normal services.
- [ ] Payment remains outside autonomous AI control.
- [ ] Provider 401/403/429/5xx/timeout/model-deprecation failures produce bounded, user-safe AI errors.
- [ ] No raw JWT, Groq API key, payment token, or sensitive customer data is logged or sent unnecessarily to the model.
- [ ] Frontend displays structured results and never treats model prose as authoritative booking state.
- [ ] Focused backend/frontend/integration tests pass.
- [ ] One live Groq smoke test demonstrates: natural-language search -> deterministic seat proposal -> explicit confirm -> reservation hold -> normal checkout handoff.
