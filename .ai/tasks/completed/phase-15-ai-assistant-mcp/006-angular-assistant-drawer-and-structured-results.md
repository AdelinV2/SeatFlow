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

Add the SeatFlow AI assistant as a discoverable Angular drawer/panel that renders conversation text plus structured Event/Session/Seat/Proposal/Reservation cards returned by `ai-service`.

The UI must make these states visibly distinct:

1. AI suggestion/search result;
2. server-side reservation proposal — **seats not held**;
3. actual Reservation Service 15-minute hold;
4. normal checkout/payment.

Angular never calls Groq directly and never contains provider credentials.

---

## 3. Authentication Policy

Phase 15 interactive assistant is authenticated-user only.

- authenticated USER may chat and confirm proposals;
- logged-out users cannot call chat/confirmation APIs;
- launcher may be hidden while logged out or show a deterministic sign-in-required state consistent with current shell UX;
- do not implement guest AI reservation/email-proof behavior;
- frontend visibility is convenience only; backend auth remains authoritative.

---

## 4. Entry Point / Responsive Layout

Reuse existing SeatFlow shell/design primitives.

Recommended:

- desktop: assistant launcher opens right-side drawer;
- narrow/mobile: responsive full-height sheet/panel;
- close action returns focus to launcher;
- route changes use one deterministic policy: **keep the drawer open within authenticated SeatFlow navigation unless checkout/sign-out starts, where it closes**;
- sign-out clears local assistant UI state.

Required sections:

```text
Header
- SeatFlow Assistant
- availability indicator
- Reset conversation
- Close

Body
- conversation messages
- structured cards
- activity/error states

Composer
- message input
- Send
- starter prompts when thread empty
```

---

## 5. Frontend API Service

Create a dedicated typed service:

```text
AiAssistantApiService
- getStatus(): GET /api/ai/status
- sendMessage(request): POST /api/ai/chat
- resetConversation(id): DELETE /api/ai/conversations/{id}
- confirmProposal(id): POST /api/ai/proposals/{id}/confirm
```

All requests are same-origin through API Gateway.

Use existing auth interceptor. Component/service code must never manually read/copy JWTs unless the existing frontend architecture already requires it.

Forbidden in frontend source/config/bundle:

- `GROQ_API_KEY`;
- Groq Authorization header;
- direct `api.groq.com` request;
- backend system prompt;
- server proposal internals beyond returned public DTO.

---

## 6. Typed Contracts

Use strict interfaces/discriminated unions, no `any` for authoritative data.

```text
AssistantState
AssistantChatRequest/Response
AssistantCard =
  EVENT
  | SESSION
  | SEAT_SET
  | RESERVATION_PROPOSAL
  | RESERVATION_CREATED
  | INFO
AssistantError
AiFeatureStatus
```

Rules:

- unknown future card type -> safe unsupported/info rendering;
- money = minor units + currency, formatted centrally;
- timestamp/timezone uses existing SeatFlow date utilities;
- real hold countdown uses returned `expiresAt`, never `Date.now()+15m`;
- never infer seat adjacency from labels/prose; use backend `contiguous` boolean only.

---

## 7. Conversation Lifecycle

Frontend thread is presentation state, not authorization state.

Exact Phase 15 policy:

- keep thread + `conversationId` in Angular memory for current SPA lifetime;
- do **not** persist raw conversation content or active proposal IDs to `localStorage`;
- full browser refresh starts a new conversation;
- `Reset conversation` calls `DELETE /api/ai/conversations/{conversationId}`, then clears UI only after success or safe not-found/expired reconciliation;
- reset invalidates unconfirmed proposal server-side through P15-005 integration;
- reset never cancels a real reservation already created;
- sign-out clears local assistant state immediately;
- stale proposal cards remain visibly disabled if backend returns expired/superseded state.

---

## 8. Conversation UX

Starter prompts are normal user messages, for example:

- “Find events this weekend.”
- “Find two seats together for Hamlet under 250 RON.”
- “Show the best seats close to the stage.”

Message rendering:

- plain text by default or existing sanitized markdown primitive only;
- no unsanitized `[innerHTML]`;
- no raw tool JSON;
- no provider/system reasoning;
- no stack traces/internal hostnames.

Activity states may say:

```text
Searching events…
Checking sessions…
Checking live seat availability…
Comparing seat options…
```

They must not expose internal tool payloads.

---

## 9. Structured Cards

### 9.1 Event

Display public event metadata + navigation to existing Event Detail route.

### 9.2 Session

Display exact date/time/status + normal navigation to the event/session flow.

### 9.3 Seat set

Display:

- section;
- row/seat labels;
- quantity;
- total price/currency;
- `Seats together` only if `contiguous=true`;
- deterministic backend reasons.

### 9.4 Reservation proposal

Display prominently:

- event/session;
- exact seats;
- exact total/currency snapshot;
- statement: **“These seats are not held yet.”**
- `Confirm reservation` button;
- `Find different seats`/dismiss action.

Confirm action sends **only proposal ID in URL**, no seat IDs/session/price body.

While confirm is in flight:

- disable confirm control;
- show one progress state;
- prevent keyboard/double-click duplicate UI requests where possible;
- backend idempotency remains authoritative.

Typing `yes`, `confirm`, or `book it` in composer never calls confirmation endpoint automatically. It may receive a response re-presenting the card.

### 9.5 Reservation created

Only after authoritative confirm success display:

- confirmed hold state;
- seats;
- authoritative total/currency;
- countdown from `expiresAt`;
- `Continue to checkout` route into existing flow.

Never render `Reserved` from model prose alone.

---

## 10. Conflict / Staleness UX

Handle codes explicitly:

- `PROPOSAL_EXPIRED` -> disable card, offer fresh recommendation.
- `PROPOSAL_SUPERSEDED` -> disable old card.
- `SEATS_NO_LONGER_AVAILABLE`/`STALE_PROPOSAL` -> no auto-substitution; request fresh options.
- `PRICE_CHANGED` -> show pricing changed; require new proposal/confirmation.
- `RESERVATION_CONFLICT` -> same no-substitution policy.
- `RESERVATION_RESULT_UNKNOWN_RETRY_SAFE` -> do not claim success/failure; expose backend-approved retry/reconcile action only.

---

## 11. Provider / Feature Failure UX

Map backend states:

```text
AI_DISABLED
AI_MISCONFIGURED
AI_RATE_LIMITED
AI_PROVIDER_TIMEOUT
AI_PROVIDER_UNAVAILABLE
AI_MODEL_UNAVAILABLE
```

- disabled/misconfigured -> assistant unavailable, rest of app normal;
- rate-limited -> temporary unavailable/retry-later message, no account quota details;
- provider timeout/outage -> bounded retry action;
- model unavailable -> configuration/unavailable message, no raw provider payload;
- do not continuously poll/retry provider.

`GET /api/ai/status` should be lazy on first launcher open or otherwise non-blocking; AI status must not delay normal app bootstrap.

---

## 12. Accessibility

- launcher accessible name;
- proper drawer/dialog semantics based on existing component primitive;
- focus moves into drawer and returns on close;
- Escape behavior predictable;
- Enter sends, Shift+Enter inserts newline (unless current app has documented opposite convention);
- async state announced with polite live region;
- errors readable by screen reader;
- no icon-only ambiguous critical actions;
- confirmation card wording remains explicit without relying on color.

---

## 13. Performance / Bounds

- respect backend 2000-character max input and add matching client validation;
- prevent sending while identical request already in flight for same composer turn;
- no giant third-party chat UI library solely for message bubbles;
- virtual scrolling unnecessary unless bounded response/thread still exceeds practical DOM size;
- maintain composer usability on narrow/mobile viewport;
- no full seat inventory rendering inside assistant when backend already returns ranked candidates.

---

## 14. Expected File Inventory

Use current Angular feature organization and shared UI primitives. Likely:

- `[NEW]` AI assistant feature directory;
- `[NEW]` typed model file(s);
- `[NEW]` `AiAssistantApiService`;
- `[NEW]` drawer/container;
- `[NEW]` message list/bubble if justified;
- `[NEW]` event/session/seat/proposal/reservation card components;
- `[MODIFY]` authenticated app shell/header/layout for launcher;
- `[NEW]` component/service/integration tests.

Before creating components, inventory existing button/card/drawer/dialog/spinner/date/money utilities and reuse them.

---

## 15. Tests

Mandatory:

1. frontend contains no Groq key/direct provider URL call.
2. logged-out user cannot call chat/confirm.
3. disabled/misconfigured status renders unavailable state without breaking shell.
4. starter prompt sends standard chat request.
5. 2001-char input blocked client-side; backend remains final validator.
6. seat card uses backend `contiguous` exactly.
7. proposal card says seats not held.
8. confirm sends POST to proposal-ID endpoint with no seat/price payload.
9. confirm button disabled during request.
10. typed `yes` does not call confirm endpoint.
11. expired/superseded/stale/price-changed card behavior is safe.
12. reservation card/countdown uses backend `expiresAt`.
13. checkout navigation uses existing route.
14. reset calls DELETE endpoint, clears UI, and never invokes reservation cancellation.
15. sign-out clears thread state.
16. raw HTML/script from assistant text is not executed.
17. rate-limit/provider failure messages are safe.
18. unknown card type fails gracefully.
19. keyboard/focus/accessibility behavior passes focused tests.
20. desktop + narrow viewport integration smoke.

Add mocked integration flow:

```text
chat -> seat proposal -> explicit confirm -> reservation-created card -> checkout navigation
```

---

## 16. Acceptance Criteria

- [ ] Assistant is discoverable and responsive.
- [ ] Frontend calls only SeatFlow `/api/ai/**`, never Groq.
- [ ] Typed contracts are used for all authoritative card/state data.
- [ ] Proposal/reservation/payment states cannot be confused visually or programmatically.
- [ ] Only explicit button/action calls confirmation endpoint.
- [ ] Reset lifecycle is exact and server-coordinated.
- [ ] Stale/conflict/price changes never auto-substitute/confirm.
- [ ] Reservation countdown uses authoritative expiry.
- [ ] Provider outage does not impair normal SeatFlow UI.
- [ ] Security/accessibility/responsive tests pass.

---

## 17. Verification

Run current repo frontend checks, at minimum:

```bash
cd frontend
npm test -- --watch=false
npm run build
```

Run lint if configured. Real Groq access is not required for automated frontend tests.
