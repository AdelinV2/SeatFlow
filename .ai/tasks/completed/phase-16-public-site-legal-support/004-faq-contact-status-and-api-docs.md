# TASK-P16-004: Implement FAQ, Contact, Public Status, and API Docs Landing Pages

## 1. Task Metadata

- **Task ID:** `TASK-P16-004`
- **Git Branch:** `feat/p16-004-support-status-api-docs`
- **Target Module:** `frontend`
- **Phase:** `Phase 16 - Public Site Completion, Legal & Support`
- **Depends On:** `TASK-P16-001`, `TASK-P16-002`, `TASK-P16-003`
- **Related Specs:** `.ai/tasks/phase-16-public-site-legal-support/000-phase-overview.md`, current health/observability architecture, current API/OpenAPI configuration
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `3`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Strong`
- **Preferred Workflow:** `standard`
- **Affected Critical Invariants:** `no fake support channel/SLA; sanitized health; no internal API exposure; signed-out public access; policy consistency`

---

## 2. Objective

Implement the remaining public support/operations surfaces:

```text
/support/faq
/support/contact
/status
/api-docs
```

The pages must be useful in the portfolio deployment without pretending SeatFlow has a staffed support organization, production SLA, commercial event-organizer network, or public access to every internal service/Swagger endpoint.

---

## 3. FAQ Contract — `/support/faq`

Create a lazy public FAQ page using the P16-001 content shell.

Recommended files:

- `[NEW]` `frontend/src/app/features/public/support/faq/faq.component.ts`
- `[NEW]` `frontend/src/app/features/public/support/faq/faq.component.html`
- `[NEW]` focused tests
- `[MODIFY]` `frontend/src/app/app.routes.ts`

FAQ topics should be derived from implemented behavior and link to the authoritative page where details matter:

- selecting seats and 15-minute temporary holds;
- 10-seat reservation limit if still current;
- guest checkout vs account checkout;
- digital tickets / QR codes / guest access;
- Stripe Test Mode in the portfolio deployment;
- payment/tax preview basics;
- refund eligibility with a short link to `/legal/refunds` rather than duplicating the full rule;
- revoked/refunded ticket behavior;
- account/login/password reset;
- staff scanner only as a role-protected capability, not customer support functionality;
- AI assistant scope after Phase 15: optional assistant, authoritative tool results, no autonomous payment, no bypass of booking rules;
- privacy/data questions linking to `/legal/privacy` and `/legal/cookies`;
- how to report a demo issue.

Avoid hardcoded claims that can drift. If a fact is defined by another policy/domain rule, summarize and link.

---

## 4. Contact Contract — `/support/contact`

Create:

- `[NEW]` `frontend/src/app/features/public/support/contact/contact.component.ts`
- `[NEW]` `frontend/src/app/features/public/support/contact/contact.component.html`
- `[NEW]` focused tests
- `[MODIFY]` route entry

Current footer wording says `Contact Event Organizers`, but SeatFlow does not implement a verified organizer-support directory. Do not preserve misleading wording merely because it already exists.

The contact page must distinguish:

1. **SeatFlow portfolio/demo feedback/support** — a real maintainer contact method supplied/configured by the project owner;
2. **Event-specific commercial support** — only present if the application actually stores/displays organizer contact information;
3. **privacy/data-rights requests** — use the real monitored contact channel established for P16-002, not an invented DPO;
4. **security reports** — provide a safe contact direction if a real channel exists; do not invite users to post secrets/tokens publicly.

### 4.1 No fake form backend

Do not create a contact form that appears to submit successfully unless a backend/email/ticket workflow actually exists.

Acceptable portfolio behavior:

- configured `mailto:` link;
- configured GitHub Issues/repository feedback link if intentionally public and appropriate;
- clear copy saying no staffed support desk/SLA exists for the demo.

If a form is implemented, it must have a real delivery path, validation, abuse protection, privacy disclosure, and failure state. Otherwise prefer links.

### 4.2 Owner configuration gate

Do not hardcode a personal email inferred from source history. Add a documented public-support contact configuration or explicit content placeholder that blocks production-ready completion until the project owner supplies the intended channel.

---

## 5. Public Status Contract — `/status`

Create:

- `[NEW]` `frontend/src/app/features/public/status/status.component.ts`
- `[NEW]` `frontend/src/app/features/public/status/status.component.html`
- `[NEW]` focused tests
- `[MODIFY]` route entry

Reuse `SystemHealthService` only after auditing its public behavior.

Current service probes:

```text
/actuator/health
/api/venues?size=1
/api/events?size=1
```

and derives coarse states:

```text
OPERATIONAL
DEGRADED
DOWN
CHECKING
```

The public page may show:

- overall status;
- a sanitized small set of user-facing capabilities such as Event browsing / Booking gateway, only if those checks are meaningful;
- last checked time;
- retry/refresh action;
- clear statement that this is lightweight live demo health, not an SLA/status-history service.

It must not expose:

- raw Actuator JSON;
- database/Redis/Kafka/Eureka hostnames;
- internal service topology unless deliberately public;
- stack traces;
- deployment secrets;
- dependency versions that materially expand attack surface without a portfolio reason;
- health details only authenticated operators should see.

If `/actuator/health` itself exposes unsafe details, do not solve that by rendering them selectively in Angular; fix/configure the backend public health exposure or rely on safe user-facing probes.

Do not fabricate incident history, uptime percentage, or maintenance windows.

---

## 6. API Docs Landing Contract — `/api-docs`

Create:

- `[NEW]` `frontend/src/app/features/public/api-docs/api-docs.component.ts`
- `[NEW]` `frontend/src/app/features/public/api-docs/api-docs.component.html`
- `[NEW]` focused tests
- `[MODIFY]` route entry

This is a **portfolio documentation landing page**, not a route that blindly reverse-proxies every microservice Swagger UI.

Allowed content:

- high-level API domains: events, sessions, seats/reservations, payments, tickets, user/profile, refunds, AI if enabled;
- authentication overview at a safe level;
- server-authoritative invariants such as no double-booking / 15-minute holds / role protection;
- links to intentionally public OpenAPI/Swagger endpoints if current gateway/deployment explicitly exposes them safely;
- link to the public GitHub repository/docs if appropriate;
- note that admin/staff/private/internal APIs remain protected.

Forbidden:

- internal Docker hostnames/ports;
- direct service-discovery URLs;
- real bearer tokens, keys, Stripe secrets/client secrets, webhook examples with real values;
- instructions to bypass role checks;
- automatic listing of actuator/admin endpoints;
- links to non-public Swagger endpoints that fail or expose internals in production.

If no safe live Swagger endpoint exists, the page should link to repository API documentation instead of creating one just to satisfy the footer.

---

## 7. Cross-Page Support Consistency

P16-004 consumes rather than redefines legal/domain policy.

Required links:

```text
FAQ -> Terms / Privacy / Cookies / Refund / Tax / Contact
Contact -> Privacy for data-rights context
Status -> Support/Contact for demo issue reporting
API Docs -> Security / Terms as appropriate
```

The FAQ must not contain a second independently worded refund policy that could drift from P16-003.

AI FAQ wording must match Phase 15 implementation. If AI is disabled, explain it as optional/unavailable rather than pretending chat exists.

---

## 8. Accessibility / UX

- all routes public and signed-out accessible;
- FAQ accordions, if used, must use accessible buttons/expanded state and keyboard behavior;
- contact links must state destination/purpose clearly;
- status must not communicate state by color alone;
- `CHECKING`, `OPERATIONAL`, `DEGRADED`, `DOWN` require text labels;
- auto-refresh must not constantly steal focus or announce noisy updates;
- API docs code/endpoint examples must wrap/scroll safely on mobile;
- all pages use existing theme/design tokens.

---

## 9. Failure Modes to Prevent

- fake contact form that drops submissions;
- invented 24/7 support or response-time promise;
- footer says "Contact Event Organizers" when no organizer contact is available;
- `/status` dumps `/actuator/health` details;
- status page labels demo probes as a contractual SLA;
- API Docs exposes direct internal service ports;
- Swagger/admin route accidentally becomes unauthenticated;
- FAQ claims real charges occur in Stripe Test Mode;
- AI FAQ promises durable chat history despite Phase 15 in-memory design;
- privacy request instructions send users to a non-existent DPO.

---

## 10. Tests

Mandatory tests:

1. all four routes resolve signed-out;
2. FAQ contains links to authoritative legal/refund pages;
3. contact page does not show a success form unless a real submit implementation exists;
4. contact/support text contains no invented SLA/organization claim;
5. status handles `CHECKING`, `OPERATIONAL`, `DEGRADED`, and `DOWN` without throwing;
6. status renders only sanitized fields and never serializes raw health responses;
7. API docs page contains no internal `localhost:<service-port>` / Docker service URL inventory unless explicitly intended as repository-only documentation;
8. no protected API route/Swagger security configuration is weakened by this frontend task;
9. external links use safe attributes where applicable;
10. light/dark/mobile rendering and frontend build pass.

---

## 11. Acceptance Criteria

- [ ] `/support/faq` is useful and aligned with real SeatFlow flows.
- [ ] `/support/contact` uses only a real/configured contact path and makes demo support limits clear.
- [ ] No fake organizer-support directory or fake contact backend is introduced.
- [ ] `/status` provides useful coarse health without leaking infrastructure details or claiming an SLA.
- [ ] `/api-docs` is a safe portfolio landing page and exposes only intentionally public documentation.
- [ ] All pages are public, responsive, accessible, themed, and use the P16-001 shell.
- [ ] FAQ links to authoritative policy pages rather than duplicating rules.
- [ ] Focused tests + frontend build pass.

---

## 12. Verification

```bash
cd frontend
npm test -- --watch=false
npm run build
```

Manual checks:

```text
signed out -> /support/faq loads
signed out -> /support/contact loads
backend healthy -> /status shows sanitized operational state
backend unavailable -> /status fails gracefully without raw errors
/api-docs -> no internal/private endpoint leakage
```
