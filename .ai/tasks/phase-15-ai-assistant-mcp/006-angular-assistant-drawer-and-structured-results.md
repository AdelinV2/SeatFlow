# TASK-P15-006: Implement Angular Assistant Drawer and Structured Result UX

## 1. Task Metadata

- **Task ID:** `TASK-P15-006`
- **Git Branch:** `feat/p15-006-angular-ai-assistant`
- **Target Module:** `frontend`
- **Phase:** `Phase 15 - AI Assistant & Controlled Tool Calling`
- **Depends On:** `TASK-P15-005`
- **Related Specs:** `.ai/tasks/phase-15-ai-assistant-mcp/000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-014-ai-assistant-tool-orchestration.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `4`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `High`
- **Preferred Workflow:** `standard+security`
- **Affected Critical Invariants:** `explicit confirmation UI; authoritative structured state; no provider secret in browser; auth boundary; no unsafe HTML; no fabricated booking state`

---

## 2. Objective

Add the user-facing SeatFlow AI assistant as a discoverable Angular drawer/panel that renders conversation text plus structured Event/Session/Seat/Proposal/Reservation cards returned by `ai-service`.

The UI must make a clear distinction between:

- an AI suggestion;
- a server-side reservation proposal;
- an actual confirmed 15-minute reservation;
- normal checkout/payment.

The frontend never calls Groq directly and never contains a Groq API key.

---

## 3. Authentication Policy

For Phase 15, the interactive assistant is authenticated-user only.

Rules:

- authenticated USER may open/use the assistant;
- logged-out/guest users do not receive state-changing AI capabilities;
- recommended UI behavior for logged-out users: launcher may show a concise sign-in-required state or be hidden according to the existing design system;
- do not implement a guest AI reservation flow in this task;
- do not add frontend role tricks as authorization. Backend remains authoritative.

---

## 4. Entry Point and Layout

Add one discoverable assistant entry point consistent with the current SeatFlow shell. Recommended behavior:

- desktop: floating or header assistant button opens a right-side drawer;
- mobile/narrow widths: full-height/full-width sheet or responsive panel;
- panel remains within application focus management and does not break existing navigation;
- route changes may close or preserve the panel according to a deterministic documented policy.

Do not create a standalone fake ChatGPT clone page unless the existing layout makes a drawer technically unsuitable.

Required panel sections:

```text
Header
- SeatFlow Assistant title
- availability/disabled indicator
- reset conversation action
- close action

Conversation body
- user messages
- assistant messages
- structured cards
- loading/tool progress states
- recoverable error states

Composer
- text input/textarea
- send button
- starter prompts when empty
```

---

## 5. Frontend API Layer

Create a dedicated service, for example:

```text
AiAssistantApiService
- getStatus()
- sendMessage(request)
- confirmProposal(proposalId)
```

All calls go through same-origin API Gateway routes under `/api/ai/**`.

Do not add `GROQ_API_KEY`, Groq base URL, model credentials, or direct `https://api.groq.com` calls anywhere in Angular.

Use the existing auth interceptor/JWT mechanism rather than manually copying tokens into component code.

---

## 6. Typed Frontend Contracts

Mirror backend response contracts with explicit discriminated unions.

Recommended types:

```text
AssistantState
AssistantMessageDto
AssistantCard =
  | EventAssistantCard
  | SessionAssistantCard
  | SeatSetAssistantCard
  | ReservationProposalAssistantCard
  | ReservationCreatedAssistantCard
  | InfoAssistantCard
AssistantError
```

Rules:

- unknown future card types fail gracefully as unsupported informational content;
- never use `any` for authoritative reservation/proposal data;
- money arrives as minor units + currency and is formatted with an explicit utility;
- timestamps are parsed/displayed using existing timezone conventions;
- `expiresAt` for a real reservation is rendered from backend data, not calculated as `now + 15m`.

---

## 7. Conversation UX

### 7.1 Starter prompts

Examples only; they are UI convenience, not hardcoded business behavior:

- “Find events this weekend.”
- “Find two seats together for Hamlet under 250 RON.”
- “Show me the best seats close to the stage.”

Starter prompts call the same chat endpoint as typed user text.

### 7.2 Message rendering

- render model text as plain text or sanitized supported markdown using existing safe application patterns;
- do not use unsanitized `innerHTML`;
- do not render hidden reasoning/chain-of-thought/provider payloads;
- preserve readable line breaks without interpreting arbitrary HTML/script.

### 7.3 Loading states

Show user-friendly activity, for example:

```text
Searching events…
Checking sessions…
Checking live seat availability…
Comparing seat options…
```

Do not expose internal method names, service hostnames, raw JSON, traces, or tool arguments containing IDs unless they are normal user-facing identifiers.

---

## 8. Structured Cards

### 8.1 Event card

Display public event summary and a normal navigation action to the Event Detail route.

### 8.2 Session card

Display start/end time/status and an action to view the normal session/event flow when appropriate.

### 8.3 Seat-set card

Display:

- section;
- row/seat labels;
- quantity;
- total formatted price;
- currency;
- `Seats together` only when backend `contiguous=true`;
- deterministic recommendation reasons from backend.

Never infer adjacency from the text response.

### 8.4 Reservation proposal card

This is the most security-sensitive UI state.

Display prominently:

- event/session date/time;
- exact seats;
- exact total/currency snapshot;
- notice that seats are **not held yet**;
- `Confirm reservation` button;
- cancel/dismiss/new-search action.

The Confirm button calls only:

```text
POST /api/ai/proposals/{proposalId}/confirm
```

It must not send editable seat IDs or price.

Disable the button while request is in flight to prevent accidental double click; backend idempotency remains required regardless.

### 8.5 Reservation-created card

After authoritative success display:

- reservation ID only if useful;
- exact seats;
- authoritative hold expiration;
- countdown derived from `expiresAt`;
- `Continue to checkout` action routing to the existing checkout flow.

Do not show “reserved” before this backend result is received.

---

## 9. Stale / Conflict UX

Handle explicit backend states rather than generic failure banners.

Required examples:

### `STALE_PROPOSAL` / `SEATS_NO_LONGER_AVAILABLE`

Show:

> These seats changed before confirmation. Ask the assistant for fresh options.

Do not auto-confirm replacements.

### `PRICE_CHANGED`

Show that pricing changed and require a new proposal/confirmation.

### `PROPOSAL_EXPIRED`

Disable old confirmation card and offer fresh search/recommendation.

### `RESERVATION_RESULT_UNKNOWN_RETRY_SAFE`

Do not claim failure or success. Offer the backend-defined safe retry/reconciliation action; never create a new proposal automatically.

---

## 10. Provider/Feature Failure UX

Map stable backend errors:

```text
AI_DISABLED
AI_MISCONFIGURED
AI_RATE_LIMITED
AI_PROVIDER_TIMEOUT
AI_PROVIDER_UNAVAILABLE
AI_MODEL_UNAVAILABLE
```

Behavior:

- disabled/misconfigured -> assistant unavailable message; rest of SeatFlow remains normal;
- rate limited -> explain temporarily unavailable without showing account/quota secrets;
- timeout/provider unavailable -> retry action with bounded UX;
- model unavailable -> generic configuration/unavailable state, no raw model-provider payload.

Do not continuously poll/retry Groq through the backend.

---

## 11. Conversation Lifecycle

Frontend conversation history is UI state only.

Recommended Phase 15 policy:

- keep current conversation in Angular memory while the application session/page is active;
- store only `conversationId` in component/application state as needed;
- a full page reload may start a fresh conversation;
- Reset action clears UI thread and requests/causes backend conversation reset if such endpoint is implemented;
- never use `localStorage` for raw AI conversation content in this phase;
- do not restore stale proposal confirmation after reload.

If the existing app has a safe session-state abstraction, it may be reused, but proposal authority remains server-side.

---

## 12. Accessibility Requirements

- launcher has accessible name;
- drawer has correct dialog/region semantics consistent with component library;
- keyboard focus moves into the opened panel and returns to launcher on close;
- Escape behavior is predictable unless a confirmation request is actively blocking;
- composer usable by keyboard; Enter/Shift+Enter behavior documented;
- loading state announced with non-disruptive live region;
- error state is readable by screen readers;
- buttons have text/aria labels, not icon-only ambiguity;
- contrast/spacing follow existing design tokens.

---

## 13. Responsive / Performance Requirements

- assistant must not block initial application bootstrap on a Groq call;
- status request may be lazy/on first open;
- do not load huge chat libraries for basic rendering;
- list rendering should remain bounded because backend conversation response is bounded;
- no uncontrolled scroll growth/layout shifts;
- on mobile, composer remains visible above virtual keyboard where practical.

---

## 14. Expected File Inventory

Use the existing Angular feature organization. Likely additions:

- `[NEW]` AI assistant feature folder under `frontend/src/app/features/...`;
- `[NEW]` assistant API service;
- `[NEW]` typed AI models;
- `[NEW]` drawer/container component;
- `[NEW]` message list/message bubble components as justified;
- `[NEW]` event/session/seat/proposal/reservation card components;
- `[NEW]` price/date formatting helper only if not already reusable;
- `[MODIFY]` authenticated application shell/header/layout to expose the launcher;
- `[NEW]` component/service/integration tests.

Before adding components, inventory current shared card/button/dialog/drawer primitives and reuse them instead of duplicating design-system controls.

---

## 15. Tests

Mandatory frontend tests:

1. no Groq URL/key/model secret appears in frontend configuration/service code.
2. logged-out user cannot execute assistant chat/confirmation.
3. status `DISABLED/MISCONFIGURED` renders unavailable state without affecting app shell.
4. starter prompt sends normal chat request.
5. structured seat card renders backend `contiguous` truth exactly.
6. proposal card says seats are not held yet.
7. Confirm sends only proposal ID and does not send seat IDs/price.
8. confirm button disables while request is pending.
9. stale/expired proposal disables confirmation and requests fresh proposal.
10. successful reservation uses backend `expiresAt` for countdown.
11. checkout action navigates to existing checkout flow.
12. typed chat “yes” alone does not call confirmation endpoint.
13. raw HTML/script in assistant text is not executed.
14. rate-limit/provider failures render safe user messages.
15. unknown card type fails gracefully.
16. keyboard/focus behavior for drawer passes focused accessibility tests.
17. responsive smoke test for desktop + narrow viewport.

Add a focused integration test that walks mocked responses through:

```text
chat -> seat proposal -> explicit confirm -> reservation-created card -> checkout navigation
```

---

## 16. Acceptance Criteria

- [ ] Assistant is discoverable and consistent with SeatFlow UI.
- [ ] Frontend communicates only with `ai-service` via gateway.
- [ ] No provider secret/direct Groq call exists in browser code.
- [ ] Conversation, errors and structured cards use typed contracts.
- [ ] Proposal vs reservation vs checkout states are visually unambiguous.
- [ ] Only explicit Confirm action calls proposal confirmation endpoint.
- [ ] Stale/price-change/conflict states never auto-substitute and auto-confirm seats.
- [ ] Real reservation countdown uses authoritative `expiresAt`.
- [ ] Provider outages do not impair normal SeatFlow UI.
- [ ] Accessibility/security tests pass.

---

## 17. Verification

Run the repo's current frontend checks. At minimum:

```bash
cd frontend
npm test -- --watch=false
npm run build
```

Run lint if configured in `package.json`, plus the focused integration tests added by this task.

A real Groq key is not required for frontend automated tests.
