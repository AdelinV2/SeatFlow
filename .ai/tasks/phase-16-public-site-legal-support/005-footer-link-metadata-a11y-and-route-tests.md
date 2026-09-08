# TASK-P16-005: Finalize Footer Links, Public Metadata, Accessibility, and Route Regression Tests

## 1. Task Metadata

- **Task ID:** `TASK-P16-005`
- **Git Branch:** `feat/p16-005-public-footer-metadata-a11y-tests`
- **Target Module:** `frontend`
- **Phase:** `Phase 16 - Public Site Completion, Legal & Support`
- **Depends On:** `TASK-P16-001`, `TASK-P16-002`, `TASK-P16-003`, `TASK-P16-004`
- **Related Specs:** `.ai/tasks/phase-16-public-site-legal-support/000-phase-overview.md`, all P16 task files, existing public layout/components
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `4`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Strong`
- **Preferred Workflow:** `strong`
- **Affected Critical Invariants:** `no broken footer routes; no fabricated company identity; truthful public claims; metadata correctness; signed-out reachability; accessibility; no wildcard masking`

---

## 2. Objective

Perform the final public-site integration pass after all Phase 16 pages exist.

This task must make every footer link intentional, remove misleading/fabricated public claims, add consistent route/page metadata, close accessibility gaps, and add a regression suite that proves legal/support routes cannot silently disappear behind the wildcard 404 or redirect behavior.

---

## 3. Mandatory Footer Audit

Primary files:

- `[MODIFY]` `frontend/src/app/shared/layout/footer/footer.component.html`
- `[MODIFY]` `frontend/src/app/shared/layout/footer/footer.component.ts` as required
- `[MODIFY/NEW]` footer tests

Audit **every** link and every public claim in the footer against current implementation.

### 3.1 Known baseline issues to fix

The 2026-09-08 `develop` footer currently contains:

```text
© <year> SeatFlow Inc. All rights reserved.
```

SeatFlow is a portfolio/demo project and no incorporated `SeatFlow Inc.` legal entity is defined in the repository. Remove the fabricated `Inc.` identity. Use neutral wording such as `SeatFlow — portfolio project` or another owner-approved factual label.

The footer currently also says:

```text
Premium live-event ticketing ... zero double-booking guarantee.
```

The backend has a critical no-double-booking invariant, but public legal/marketing copy should avoid converting an engineering invariant into an unconditional commercial guarantee. Prefer factual wording such as "server-enforced seat reservation designed to prevent double booking" or equivalent reviewed copy.

The current support list includes:

```text
Guest Ticket Lookup -> /auth/login
```

This is misleading because login is not a guest ticket lookup. Replace/remove/re-route it based on actual guest-ticket UX. Do not invent a generic lookup route if only signed token/ticket-code access exists.

The footer currently labels:

```text
/legal/cookies -> Cookie Preferences
```

P16-002 decides whether real optional consent preferences exist:

- if `CONSENT_UI_DECISION = NOT_REQUIRED_FOR_CURRENT_RUNTIME`, label the link `Cookies & Storage` (or equivalent disclosure wording), not `Preferences`;
- if `CONSENT_UI_DECISION = REQUIRED`, the footer may expose both the policy route and a real `Manage privacy/cookie preferences` action wired to the consent manager.

### 3.2 Footer destination contract

After Phase 16, every footer navigation item must be one of:

1. a valid Angular route;
2. a deliberate external link with correct target/rel behavior;
3. a real preference action/button when P16-002 implements consent.

No link may depend on wildcard fallback to appear functional.

Audit these current destinations:

```text
/events + category query links
/profile/tickets
/scanner
/support/faq
/support/contact
/legal/terms
/legal/privacy
/legal/tax
/legal/refunds
/legal/cookies
/legal/security
/status
/api-docs
```

Also audit whether authenticated/protected destinations belong in the public footer. A protected link is allowed if the label is truthful and guard behavior is intentional; do not expose admin links merely for completeness.

---

## 4. Public Route Metadata

Implement one coherent metadata approach for Phase 16 routes. Do not scatter raw `document.title = ...` mutations through each component.

At minimum configure distinct titles for:

```text
Terms
Privacy
Cookies & Storage
Security
Refund & Cancellation Policy
Tax / Stripe Test Mode
FAQ
Contact
Status
API Docs
404
```

Add concise meta descriptions where the existing Angular architecture supports them cleanly.

Requirements:

- metadata must update on client-side navigation;
- 404 should be `noindex` where practical;
- legal pages should not claim certification/compliance in `<title>`/description if body copy carefully avoids that claim;
- status metadata should not encode transient live status into page title unless implemented accessibly and intentionally;
- no user email, reservation ID, ticket code, or auth callback data enters metadata.

Recommended implementation options:

- Angular route `title` fields plus a small centralized meta service for descriptions/noindex; or
- the existing project-standard metadata mechanism if one already exists.

Do not add a heavy SEO library for ten static pages.

---

## 5. Accessibility Final Pass

Run a manual and automated-focused accessibility review across every Phase 16 public page.

Verify:

### Structure

- one `h1` per page;
- headings do not skip logical levels;
- `main`, `nav`, and `footer` landmarks are sensible and not duplicated incorrectly;
- long legal tables have headers/captions or accessible equivalents.

### Keyboard / focus

- all links/buttons reachable by keyboard;
- FAQ accordion keyboard behavior works;
- route navigation results in useful focus behavior;
- table-of-contents anchors are usable with sticky header;
- consent/preferences dialog, **if it exists**, traps/restores focus correctly and is closable according to the chosen consent UX without making refusal harder.

### Status / semantics

- status and validation state never rely on color alone;
- external links indicate external destinations when helpful;
- footer navigation groups have unique accessible labels;
- icons/decorative elements have correct `aria-hidden` behavior;
- support/contact instructions are understandable without visual context.

### Visual

- 200% zoom does not make legal text unusable;
- 320px viewport does not horizontally overflow except intentional scrollable code/table regions;
- light/dark/system themes preserve contrast;
- reduced-motion preference is respected.

Do not claim formal WCAG conformance unless a real conformance audit has been performed. The task goal is to fix identified accessibility defects, not add a badge.

---

## 6. System Health Footer Accuracy

`SystemHealthService` currently initializes status to `OPERATIONAL` before the first asynchronous check completes.

Review this behavior because the footer can briefly claim `All Systems Operational` without evidence.

Preferred behavior:

```text
initial state = CHECKING
first completed probe -> OPERATIONAL / DEGRADED / DOWN
```

If changed:

- preserve SSR/browser safety;
- keep tests deterministic;
- avoid noisy polling announcements for assistive technology;
- do not add persistent browser storage for health state;
- do not turn the footer into an SLA indicator.

P16-004 `/status` and footer must share coherent sanitized state semantics.

---

## 7. Public Copy Consistency Review

Search the frontend/public docs for claims that conflict with Phase 16 truthfulness rules, especially:

```text
SeatFlow Inc
GDPR compliant
GDPR certified
PCI compliant/certified
ISO
SOC 2
99.9%
24/7 support
guaranteed refund
zero double-booking guarantee
real payment
production payment
Cookie Preferences
Contact Event Organizers
```

Do not mechanically delete valid technical documentation. Review **public user-facing copy** and any footer/landing content surfaced in the app.

Also check:

- Stripe Test Mode wording is consistent;
- AI is described as optional/assistive and only when enabled;
- privacy page does not say "no cookies" while cookies/storage inventory says otherwise;
- retention/provider/contact facts match P16-002 inventory;
- refund cutoff matches P16-003;
- support promises match P16-004.

---

## 8. Route / Footer Regression Test Matrix

Add a focused route regression suite, recommended as Angular router tests and optionally one lightweight browser/E2E-style smoke suite if the repo already has such infrastructure.

### 8.1 Public routes that must resolve signed-out

```text
/
/events
/legal/terms
/legal/privacy
/legal/tax
/legal/refunds
/legal/cookies
/legal/security
/support/faq
/support/contact
/status
/api-docs
/<unknown> -> 404
```

### 8.2 Existing protected routes must remain protected

At minimum verify existing behavior for representative routes:

```text
/profile/tickets -> authGuard
/scanner -> staffGuard
/admin -> adminGuard
```

P16 must not weaken these guards to make footer-link tests easier.

### 8.3 Footer link integrity

Tests must enumerate or query all router links rendered by the footer and assert that each deliberate internal destination maps to a configured route/guard behavior rather than wildcard fallback.

For category query links, verify query parameters remain correct.

If a consent preference button exists, assert it is a button/action, not a fake router link.

---

## 9. Cookie / Privacy Regression Hooks

P16-005 does not reimplement consent logic, but it must protect the P16-002 decision from accidental UI drift.

If no consent is required:

- footer must not show fake `Cookie Preferences` action;
- no banner appears on first visit;
- `/legal/cookies` remains reachable.

If consent is required:

- footer provides a persistent manage-preferences action;
- it reopens the real consent manager;
- refusal/withdrawal tests from P16-002 continue to pass.

In either case, footer/navigation must not create a new tracking/storage identifier.

---

## 10. Landing/Public Navigation Review

Phase 16 is not a redesign of the event catalog, but review the public header/footer/landing surfaces for missing Phase 16 navigation and misleading trust copy.

Allowed adjustments:

- add Help/Support where useful;
- ensure legal/privacy links are easy to find in footer;
- add a small demo/Test Mode disclosure where current checkout/public marketing could otherwise imply real commerce;
- align public CTA wording with actual app capabilities.

Do not bury the event-browsing CTA under legal content or turn the home page into a compliance portal.

---

## 11. Failure Modes to Prevent

- `SeatFlow Inc.` remains visible with no such entity;
- broken footer links silently show 404 or home;
- guest lookup link still goes to login under a misleading label;
- `Cookie Preferences` is displayed when there is no preference UI;
- initial footer claims operational status before health check;
- route test treats wildcard 404 as proof a link exists;
- public route is accidentally placed behind auth guard;
- existing admin/staff guard is weakened;
- metadata leaks ticket/reservation/auth values;
- accessibility fixes create a second design system;
- public copy claims formal GDPR/PCI/ISO/SOC certification.

---

## 12. Tests

Mandatory tests/review evidence:

1. all public Phase 16 routes resolve signed-out;
2. unknown route renders the real P16-001 404;
3. every internal footer destination resolves to the intended route/guard, not wildcard;
4. footer contains no `SeatFlow Inc.` unless the owner later provides evidence/configuration for that legal entity;
5. guest-ticket footer wording/destination is truthful;
6. cookie/footer behavior matches P16-002 consent decision;
7. SystemHealthService does not falsely report `OPERATIONAL` before first check if the review changes initial state to `CHECKING`;
8. route titles update correctly across all Phase 16 pages;
9. 404 noindex behavior works if implemented;
10. representative protected routes keep guards;
11. public content contains no known fake-certification/SLA strings;
12. keyboard/heading/status semantic tests for shared components pass;
13. light/dark/mobile smoke checks pass;
14. frontend build passes.

---

## 13. Acceptance Criteria

- [ ] Footer contains no fabricated `SeatFlow Inc.` identity.
- [ ] Public marketing/trust wording does not convert technical invariants into unsupported commercial guarantees.
- [ ] Every footer link/action is real, intentional, and accurately labeled.
- [ ] Cookie/footer wording exactly matches the P16-002 consent decision.
- [ ] Guest-ticket navigation is no longer mislabeled as login.
- [ ] Status footer/page do not claim health before a real check.
- [ ] All Phase 16 pages have distinct, safe metadata.
- [ ] Unknown URLs show 404; public legal/support pages never rely on wildcard behavior.
- [ ] Existing protected-route guards remain intact.
- [ ] Accessibility review fixes all Phase 16 defects found in scope.
- [ ] Route/footer regression tests prevent reintroduction of broken legal/support links.
- [ ] No fake certification, SLA, legal entity, or support guarantee remains in public Phase 16 surfaces.
- [ ] Frontend tests and build pass.

---

## 14. Verification

```bash
cd frontend
npm test -- --watch=false
npm run build
```

Manual signed-out smoke matrix:

```text
/legal/terms
/legal/privacy
/legal/cookies
/legal/security
/legal/refunds
/legal/tax
/support/faq
/support/contact
/status
/api-docs
/not-a-real-route
```

Then run representative protected-route checks without weakening authentication:

```text
/profile/tickets
/scanner
/admin
```

Phase 17 owns the final complete cross-service/E2E quality gate, but Phase 16 is not complete until its own route, policy, metadata, footer, and accessibility regression suite is green.
