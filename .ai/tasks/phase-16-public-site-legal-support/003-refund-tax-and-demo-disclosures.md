# TASK-P16-003: Implement Refund, Tax, and Portfolio/Test-Mode Disclosures

## 1. Task Metadata

- **Task ID:** `TASK-P16-003`
- **Git Branch:** `feat/p16-003-refund-tax-demo-disclosures`
- **Target Module:** `frontend`
- **Phase:** `Phase 16 - Public Site Completion, Legal & Support`
- **Depends On:** `TASK-P16-001`, `TASK-P16-002`, Phase 13 final refund behavior
- **Related Specs:** `.ai/tasks/phase-16-public-site-legal-support/000-phase-overview.md`, `.ai/tasks/phase-13-refunds-ticket-cancellation/000-phase-overview.md`, `.ai/decisions/ADR-012-refund-cutoff-and-ticket-revocation.md`, payment/tax implementation on current `develop`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `3`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Strong`
- **Preferred Workflow:** `standard`
- **Affected Critical Invariants:** `24-hour refund rule accuracy; no fake refund guarantee; Stripe Test Mode transparency; tax wording matches implementation; no duplicated conflicting policy`

---

## 2. Objective

Implement public `/legal/refunds` and `/legal/tax` pages that explain SeatFlow's **implemented** refund/cancellation and tax/payment behavior without turning portfolio/demo behavior into fake commercial promises.

The pages must derive their rules from server-authoritative Phase 13/payment behavior. They are explanatory surfaces only; they must not become an independent source of truth that can drift from backend policy.

---

## 3. Refund Policy Contract — `/legal/refunds`

Create:

- `[NEW]` `frontend/src/app/features/public/legal/refunds/refunds.component.ts`
- `[NEW]` `frontend/src/app/features/public/legal/refunds/refunds.component.html`
- `[NEW]` focused tests
- `[MODIFY]` `frontend/src/app/app.routes.ts`

Use the P16-001 content shell.

### 3.1 Canonical current rule

The page must state the current Phase 13 product rule accurately:

- customer self-service refund applies to a confirmed/paid eligible reservation;
- initial scope is a **full-reservation refund**, not partial/per-ticket refund;
- the authoritative cutoff is server-side;
- a refund is eligible when **at least 24 hours remain before the purchased event session starts**;
- exactly `24:00:00` remaining is eligible;
- below 24 hours is not eligible under the current policy;
- successful refund revokes/cancels the affected tickets and, when the session remains bookable, releases seats according to the implemented workflow;
- a failed provider refund must not be described as completed merely because the UI started a request.

Do not round the rule into ambiguous wording such as "one day before the event" if that could contradict exact instant-based behavior.

### 3.2 Ownership / guest behavior

Describe only what the current implementation actually exposes:

- authenticated owner refund behavior;
- admin capabilities only if they are relevant to customer-facing copy and safe to mention;
- guest self-service only if Phase 13 securely implemented it;
- if guest refund self-service is absent, direct guests to the real support/contact path established by P16-004 rather than implying a hidden flow exists.

Do not expose authorization mechanics, guest proof tokens, internal IDs, or admin bypass details.

### 3.3 Workflow wording

User-facing policy may explain states in plain language:

```text
request submitted -> processing -> completed or failed
```

Avoid publishing Kafka topic names, Stripe idempotency keys, internal event names, or retry details.

If refunds are still Stripe **Test Mode** in the portfolio deployment, say clearly that no real-world refund transfer occurs in the demo.

---

## 4. Tax / Payment Disclosure Contract — `/legal/tax`

Create:

- `[NEW]` `frontend/src/app/features/public/legal/tax/tax.component.ts`
- `[NEW]` `frontend/src/app/features/public/legal/tax/tax.component.html`
- `[NEW]` focused tests
- `[MODIFY]` route entry

The page must explain the current implementation without pretending SeatFlow is giving tax/legal advice.

At minimum verify before writing final copy:

- currency behavior;
- price display behavior;
- tax preview inputs and outputs;
- whether tax uses Stripe Tax in Test Mode;
- whether a billing/tax address is persisted by SeatFlow or only processed/transmitted for preview;
- what amount/tax/net fields are stored in SeatFlow payment/ticket records;
- whether final payment amounts can change after tax calculation and which backend/provider response is authoritative;
- refund treatment of tax in the current full-refund flow.

The public page should explain categories and behavior, not expose request DTO schemas.

---

## 5. Stripe Test Mode / Demo Disclosure

The current portfolio deployment must not look like it processes real money if it uses Stripe Test Mode.

Where applicable, the refund/tax pages must clearly state:

- the site is a portfolio/demo deployment;
- Stripe Test Mode is used;
- test card/payment/refund flows do not represent real charges or transfers;
- displayed tax calculations are demonstration behavior unless/until a real commercial deployment is configured and legally validated;
- no promise is made that demo tax values are suitable for real invoicing/accounting.

Do not overuse an alarming banner on every paragraph. A clear page-level callout and relevant contextual wording are sufficient.

If the deployment later switches to Stripe live mode, this content becomes a mandatory release-review item and must not remain stale.

---

## 6. Cross-Page Consistency

P16-003 must not copy independent versions of the same rule into many components.

At minimum align:

- `/legal/terms` -> references the dedicated refund/tax pages;
- `/legal/privacy` -> describes payment/tax data processing without promising refund eligibility;
- `/legal/refunds` -> owns customer-facing refund policy details;
- `/legal/tax` -> owns tax/payment-demo explanation;
- `/support/faq` -> later links/summarizes these pages without inventing alternate rules;
- checkout/refund UI -> wording must not contradict public policy.

If a shared typed policy constant is useful for non-legal UI copy, keep it narrowly scoped. Do not try to encode the entire Terms/Privacy document as a TypeScript configuration object.

---

## 7. Server-Authoritative Rule Verification

Before final copy, inspect the actual Phase 13 implementation and tests, not only the overview.

Verify at minimum:

```text
refund cutoff comparator
clock/Instant semantics
exactly-24h boundary
reservation statuses eligible for refund
full vs partial refund scope
successful refund final states
guest refund availability
Stripe mode/configuration
refund amount semantics
```

If implementation differs from the Phase 13 overview/ADR, do not paper over the mismatch. Resolve the code/spec inconsistency first or explicitly block P16-003 until the authoritative behavior is clear.

---

## 8. Accessibility / UX

Both pages must:

- use the P16-001 content-page shell;
- have one `h1` and logical sections;
- use plain language before technical detail;
- make the 24-hour cutoff visually clear without relying only on color;
- provide links to Terms, Privacy, Support/Contact, and Events where useful;
- remain readable on mobile;
- work signed-out;
- use existing theme tokens.

A small example is allowed, e.g. "session starts Saturday at 20:00 -> request must be accepted no later than Friday at 20:00 in the relevant instant/time-zone interpretation," but only if the example cannot contradict the backend time semantics. Prefer an abstract 24-hour example over timezone-sensitive legal copy if uncertainty exists.

---

## 9. Failure Modes to Prevent

- page says "refund up to 24 hours before" while code treats exactly 24h differently;
- page implies partial ticket refunds when only full reservation refund exists;
- page guarantees a refund after request submission before Stripe success;
- guest users are told to use a refund endpoint that does not exist;
- tax page claims SeatFlow is a tax adviser or issues legally valid invoices when it does not;
- Stripe Test Mode is hidden or buried;
- public copy says card details are stored by SeatFlow without evidence;
- privacy/tax pages disagree about whether billing address is retained;
- hardcoded currency/tax statements contradict runtime configuration;
- internal provider IDs/secrets are rendered publicly.

---

## 10. Tests

Mandatory focused tests:

1. `/legal/refunds` and `/legal/tax` are public signed-out routes.
2. Refund page includes the exact `24 hours` rule and full-reservation scope.
3. Refund page does not advertise partial refunds unless implementation actually supports them.
4. If guest self-service is unavailable, page points to support/contact instead of showing a fake action.
5. Stripe Test Mode/demo disclosure is present when the deployment configuration/spec says Test Mode.
6. Tax page wording reflects the implemented tax preview boundary.
7. No secret/provider credential/client-secret value is rendered.
8. Internal links to Terms/Privacy/Support are valid after P16-004/P16-005 integration.
9. Pages render with the shared shell in light and dark modes.
10. frontend tests/build pass.

Where possible, add a small unit-level assertion around any shared refund-policy display helper so `24h` boundary wording cannot accidentally be changed to a contradictory value without a test failure.

---

## 11. Acceptance Criteria

- [ ] `/legal/refunds` accurately reflects server-authoritative Phase 13 behavior.
- [ ] Exactly 24 hours is described consistently with implementation.
- [ ] Full-reservation vs partial-refund scope is unambiguous.
- [ ] Guest refund behavior is truthful.
- [ ] Refund submission is not represented as completed until provider/domain success.
- [ ] `/legal/tax` reflects actual tax preview/payment data behavior.
- [ ] Stripe Test Mode/demo status is clear wherever applicable.
- [ ] No real commercial/tax/invoicing guarantee is invented.
- [ ] Cross-links do not create conflicting policy copies.
- [ ] Public pages expose no internal secrets/identifiers.
- [ ] Focused tests and frontend build pass.

---

## 12. Verification

```bash
cd frontend
npm test -- --watch=false
npm run build
```

Also verify the Phase 13 backend boundary tests that define refund eligibility before approving legal copy. At minimum, the source-of-truth test suite must cover:

```text
24:00:00 remaining -> eligible
23:59:59 remaining -> rejected
failed refund -> ticket remains valid / no false completion
successful refund -> final refund/ticket state matches public wording
```
