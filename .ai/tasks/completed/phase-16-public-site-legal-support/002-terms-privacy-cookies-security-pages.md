# TASK-P16-002: Implement Terms, Privacy, Cookies/Storage, Security Pages and Compliance Inventory

## 1. Task Metadata

- **Task ID:** `TASK-P16-002`
- **Git Branch:** `feat/p16-002-legal-privacy-storage-security`
- **Target Modules:** `frontend`, legal/compliance documentation, focused runtime verification
- **Phase:** `Phase 16 - Public Site Completion, Legal & Support`
- **Depends On:** `TASK-P16-001`, Phase 15 final runtime/provider behavior if AI is enabled
- **Related Specs:** `.ai/tasks/phase-16-public-site-legal-support/000-phase-overview.md`, `.ai/architecture/04-authentication-security.md`, `.ai/architecture/09-post-mvp-evolution.md`, `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-014-ai-assistant-tool-orchestration.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `Critical`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `truthful privacy disclosure; GDPR Article 13 completeness; terminal-storage classification; no fake consent; no non-essential storage before consent; no secret disclosure; signed-out access; processor accuracy; personal-data minimization`

---

## 2. Objective

Implement SeatFlow's public `/legal/terms`, `/legal/privacy`, `/legal/cookies`, and `/legal/security` pages from a verified source/runtime inventory instead of generic template copy.

This task also owns the Phase 16 browser-storage/cookie decision. It must inventory classic cookies **and** equivalent terminal storage such as `localStorage`, `sessionStorage`, and SDK-managed storage, then determine whether a consent mechanism is actually required for the deployed configuration.

A visually impressive but inaccurate cookie banner is a failure. A privacy page that omits guest email storage, Supabase session persistence, Stripe/Groq processing, or retention/right information is also a failure.

This task is engineering guidance, not a representation that a lawyer has certified SeatFlow. Any unresolved controller/company facts must be explicit release gates.

---

## 3. Legal / Technical Baseline

Implementation must verify the current versions of these primary references rather than copying blog summaries:

- GDPR, especially Articles 5, 6, 12, 13, 15–22, 32 where relevant: `https://eur-lex.europa.eu/eli/reg/2016/679/oj`
- Romanian Law 506/2004, especially Article 4(5)–(6): `https://legislatie.just.ro/Public/DetaliiDocument/288598`
- EDPB Guidelines 2/2023 on the technical scope of Article 5(3) ePrivacy Directive: `https://www.edpb.europa.eu/our-work-tools/our-documents/guidelines/guidelines-22023-technical-scope-art-53-eprivacy-directive_en`
- Supabase JS Auth persistence behavior: `https://supabase.com/docs/reference/javascript/auth`

Engineering interpretation for this task:

- terminal-storage rules are not limited to HTTP cookies;
- `localStorage` and `sessionStorage` must be audited too;
- storage/access that is strictly necessary for a user-requested service may fall under the Romanian Article 4(6) exception;
- optional analytics/advertising/profiling/non-exempt storage must not execute before the required consent;
- the privacy notice still needs truthful GDPR transparency even where terminal storage is exempt from cookie consent.

Do not use consent as the default legal basis for every processing purpose. Map each purpose separately and leave unresolved legal-basis choices as explicit review gates.

---

## 4. Mandatory Data / Storage / Provider Inventory

Before writing final page copy, create a version-controlled inventory, recommended:

- `[NEW]` `.ai/compliance/phase-16-privacy-storage-provider-inventory.md`

If `.ai/compliance` does not exist, create it. The inventory is a factual engineering artifact, not legal boilerplate.

### 4.1 Inventory columns

For each processing/storage/provider item record at minimum:

```text
item / data category
source / code path
subject type (registered user / guest / staff / admin / visitor)
purpose
where processed/stored
first-party or third-party
provider, if any
terminal-storage mechanism and key/cookie name, if applicable
when it is created/read
duration / retention or verified criteria
personal data? yes/no/conditional
security sensitivity
proposed GDPR legal basis (or NEEDS LEGAL CONFIRMATION)
terminal-storage consent required? yes/no/needs confirmation
international transfer / subprocessor facts verified? yes/no/unknown
public disclosure location
```

Unknown values must remain `UNKNOWN / MUST CONFIRM`; never replace them with plausible-sounding assumptions.

### 4.2 Minimum source audit

Audit current implementation, migrations/entities/DTOs, deployment config, frontend SDKs, and Phase 15 final code. At minimum verify:

- `frontend/src/app/core/auth/auth.service.ts`;
- `frontend/src/app/core/theme/theme.service.ts`;
- `frontend/src/index.html`;
- `frontend/src/app/services/reservation-api.service.ts`;
- Stripe integration/frontend checkout;
- user/reservation/payment/ticket entities and migrations;
- notification/email flow;
- API Gateway rate limiting / security logs;
- observability/logging configuration;
- Nominatim calls;
- Phase 15 AI provider/memory/orchestration if merged;
- production environment/Compose/runtime providers actually enabled.

Search for at least:

```text
localStorage
sessionStorage
document.cookie
Cookie
Set-Cookie
persistSession
storage
analytics
gtag
GTM
posthog
hotjar
clarity
pixel
sentry
stripe
supabase
nominatim
groq
```

A source grep is necessary but not sufficient because third-party SDKs can create terminal state at runtime.

---

## 5. Verified Starting Findings That Must Not Be Lost

The 2026-09-08 `develop` audit established these starting facts. Re-verify them during implementation.

### 5.1 Supabase Auth session persistence

SeatFlow uses:

```ts
auth: {
  persistSession: true,
  autoRefreshToken: true,
  detectSessionInUrl: true,
}
```

Supabase documents that browser `persistSession` stores the session in local storage by default. Inventory the actual runtime key(s), content category (do not publish token contents), creation time, refresh behavior, logout removal behavior, and session lifetime configuration.

Rules:

- never expose JWT/refresh-token values in the privacy/cookie page;
- never put auth storage behind a generic "analytics" toggle;
- confirm logout removes or invalidates the persisted session as intended;
- disclosure may say authentication/session information is stored locally, but should not publish exploit-relevant token details.

### 5.2 Theme preference

`seatflow_theme_mode` is stored in `localStorage` and can contain `dark`, `light`, or `system`.

Requirements:

- list it as functional preference storage;
- determine whether it is written before the user explicitly changes theme;
- prefer changing behavior so persistent theme storage is written only after a user choice where that is practical and does not introduce flicker/regressions;
- if the implementation retains automatic storage, document the concrete Article 4(6) rationale or mark the classification for legal confirmation rather than silently calling it "strictly necessary".

### 5.3 Guest checkout email in sessionStorage

`ReservationApiService` uses:

```text
key prefix: seatflow:reservation-email:
value: guest customer email
mechanism: sessionStorage
```

This is personal data and terminal storage.

Requirements:

- inventory and disclose its functional purpose accurately;
- verify it is created only for guest checkout where needed;
- verify cleanup on cancellation/completion/abandonment paths that SeatFlow can control;
- confirm browser-tab/session closure semantics;
- never include the email in URL/query parameters;
- do not log the stored value during storage debugging;
- add focused tests for creation/removal/absence on authenticated flows;
- flag the fact that email is being used as guest proof for security review; do not falsely describe it as an anonymous token.

Do not redesign the entire guest authorization architecture inside this legal-page task unless a concrete vulnerability requires it. If the review concludes an opaque proof token is required, create a narrowly scoped follow-up/security change with regression tests rather than silently altering checkout semantics.

### 5.4 No intentional marketing analytics found in baseline source scan

No deliberate Google Analytics/PostHog/Hotjar-style integration or direct first-party `document.cookie` write was identified in the baseline source scan.

Do **not** translate that into "SeatFlow uses no cookies" until runtime is tested. Supabase OAuth, Stripe.js/Elements, hosting/CDN, or future integrations may create cookies or equivalent storage in their own contexts.

---

## 6. Personal Data Categories to Verify and Disclose

The final privacy notice must be based on actual source/runtime behavior and distinguish data provided directly from data generated by use of the service.

Minimum categories to verify:

### 6.1 Account / identity

Potential implemented fields include:

- Supabase subject/user ID;
- email;
- display/name metadata;
- role(s);
- optional phone/profile fields if actually collected by current UI/API;
- account creation/update timestamps;
- OAuth provider identity metadata needed for login.

Do not claim phone is collected if the deployed UI/API never asks for or stores it.

### 6.2 Guest and authenticated booking

Potential implemented fields include:

- customer email;
- customer/attendee name where supplied;
- optional linked user ID;
- reservation ID;
- event/session and selected seat IDs;
- selected price/tier information;
- status, expiry, timestamps;
- idempotency/security metadata where retained.

### 6.3 Payments / tax

Verify and document the boundary between SeatFlow and Stripe:

- payment/reservation identifiers;
- customer email linkage;
- amount, tax amount, net amount, currency, payment/refund status, timestamps;
- Stripe PaymentIntent/provider identifiers and other server-side integration metadata actually persisted;
- billing/tax address fields that are submitted for tax preview and whether SeatFlow persists them or only transmits/processes them transiently;
- Stripe Test Mode in the public portfolio deployment if still applicable.

Do not claim SeatFlow stores card numbers/CVC if card entry is handled by Stripe and SeatFlow never receives/stores those fields. Conversely, do not claim it never processes payment-related data without verifying the actual Stripe flow.

Never expose payment `clientSecret`, webhook secrets, Stripe secret keys, or raw provider payloads on a public page.

### 6.4 Tickets

Verify fields such as:

- ticket code / ticket ID;
- reservation/payment/user/event/session/seat linkage;
- customer email;
- attendee name;
- event/venue/seat display information;
- amounts/tax/net values;
- ticket status;
- QR/ticket access data;
- issuance/use/cancellation timestamps.

Public disclosure should describe categories/purposes, not publish internal database schemas or token formats.

### 6.5 Security / operational data

Verify:

- IP-related rate-limit/security processing;
- request/correlation identifiers;
- application logs;
- traces/metrics;
- authentication/security events;
- scanner/ticket validation audit data if user-identifiable;
- error telemetry if any third-party telemetry is enabled.

Do not say "IP addresses are stored" unless persistence/logging is actually confirmed; distinguish transient Redis rate-limit keys from durable logs.

### 6.6 AI data when Phase 15 is enabled

P15 specifies no persistent chat database and bounded in-memory SeatFlow conversation memory. That does **not** mean AI is "zero data".

Verify and disclose:

- user prompts/messages sent to Groq;
- compact event/session/seat tool context included in model requests;
- conversation ID/owner metadata held by SeatFlow in memory and its configured TTL;
- whether any personal data is intentionally excluded from prompts;
- provider-side retention/training/logging policy for the exact Groq account/API configuration actually used;
- transfer/location/subprocessor facts that can be verified.

Never claim "Groq does not retain/train on data" without checking the current provider terms/configuration applicable to the deployed account.

---

## 7. Third-Party / Processor Inventory

At minimum evaluate these integrations if present in the final runtime:

### Supabase

Purpose may include authentication/account identity. Verify region, applicable privacy/subprocessor documentation, OAuth provider behavior, and which data SeatFlow sends/receives.

### Stripe

Purpose may include test/live payment intent processing, card UI/payment confirmation, tax preview, fraud/security functions, and webhooks. Verify whether the deployed site is Test Mode or live.

### OpenStreetMap Nominatim

Current frontend code calls `https://nominatim.openstreetmap.org` directly for geocoding. A direct browser request can disclose network/request metadata to that external service and may include the address/search query supplied by the user/admin. Verify whether this remains direct-browser in the final deployment and disclose or redesign if appropriate.

### Groq

Only include if Phase 15 AI is enabled. Describe AI processing narrowly and accurately.

### Hosting / email / observability

Inventory the actual production providers; do not assume local Docker architecture equals deployed processing.

For every provider, avoid calling it a GDPR "processor" unless that role is appropriate and verified. The public notice may use neutral wording such as "service provider/recipient" where legal role is not confirmed.

---

## 8. Privacy Notice Contract — `/legal/privacy`

Create a public lazy page, recommended:

- `[NEW]` `frontend/src/app/features/public/legal/privacy/privacy.component.ts`
- `[NEW]` `frontend/src/app/features/public/legal/privacy/privacy.component.html`
- `[NEW]` focused tests
- `[MODIFY]` `frontend/src/app/app.routes.ts`

The page must contain, where applicable and verified:

1. portfolio/demo context;
2. controller/operator identity and contact details **or an explicit pre-production placeholder gate** if not yet provided;
3. data categories;
4. purposes of processing;
5. legal basis per purpose;
6. recipients/categories of recipients and enabled service providers;
7. international-transfer information/safeguards only where verified;
8. retention periods or objective retention criteria;
9. user rights: access, rectification, erasure, restriction, objection where applicable, portability where applicable;
10. consent withdrawal where a processing purpose actually uses consent;
11. right to lodge a complaint with the competent supervisory authority;
12. whether provision of data is contractual/necessary for a requested transaction and consequences of not providing it where relevant;
13. automated decision-making/profiling statement only if applicable — do not pretend the AI assistant makes legally significant automated decisions;
14. security summary at a non-sensitive level;
15. links to Cookies/Storage, Terms, Refund, Security, and Contact pages.

Do not publish a "production-ready" controller section with invented company/address/DPO information.

---

## 9. Cookies & Similar Technologies Contract — `/legal/cookies`

Create:

- `[NEW]` `frontend/src/app/features/public/legal/cookies/cookies.component.ts`
- `[NEW]` `frontend/src/app/features/public/legal/cookies/cookies.component.html`
- `[NEW]` focused tests
- `[MODIFY]` route entry

The page title/copy should explicitly cover **cookies and similar browser storage**.

Include a table derived from the verified inventory with columns such as:

```text
Name / key
Technology (cookie / localStorage / sessionStorage / SDK storage)
Provider
Purpose
Category
When created
Duration
Contains/relates to personal data?
Consent required? / strictly-necessary rationale
```

Never put token contents, guest emails, or secrets in examples.

At minimum represent the verified Supabase auth storage, `seatflow_theme_mode`, and `seatflow:reservation-email:*` behavior if still present.

---

## 10. Consent Decision and Optional Consent Implementation

This task must produce one explicit decision in the inventory:

```text
CONSENT_UI_DECISION = NOT_REQUIRED_FOR_CURRENT_RUNTIME
```

or

```text
CONSENT_UI_DECISION = REQUIRED
```

with evidence and date.

### Path A — No non-exempt storage found

If runtime/source audit shows only storage that is defensibly covered by the necessary/requested-service exception:

- do not add a banner;
- publish the accurate cookies/storage page;
- document why each item is exempt/necessary;
- keep the decision easy to revisit when a new SDK/analytics integration is added.

### Path B — Non-essential/non-exempt storage exists

If any such storage exists, implement a real consent layer before finalizing this task.

Requirements:

- no non-essential SDK/storage is initialized before consent;
- first layer provides equally understandable accept/reject choices without deceptive design;
- optional categories can be managed granularly where categories exist;
- no preselected optional categories;
- withdrawal/change is available persistently from footer/settings;
- withdrawal stops future optional processing and clears/removes optional terminal state where technically appropriate;
- consent record contains only what is necessary to prove preference/version/time and does not itself become a tracking identifier;
- core auth, guest checkout, event browsing, and accessibility remain usable when optional consent is refused;
- tests verify pre-consent blocking and withdrawal.

Do not build a fake "Manage cookies" modal with toggles that control nothing.

---

## 11. Terms Contract — `/legal/terms`

Create:

- `[NEW]` `frontend/src/app/features/public/legal/terms/terms.component.ts`
- `[NEW]` `frontend/src/app/features/public/legal/terms/terms.component.html`
- `[NEW]` focused tests
- `[MODIFY]` route entry

Terms must match the current demo/product behavior, including:

- portfolio/demo nature and no false commercial entity claim;
- account and guest checkout behavior;
- event/seat reservation mechanics including temporary holds and server-authoritative availability;
- payment/test-payment boundary;
- ticket/QR access and user responsibility to protect ticket access links/codes;
- refund/cancellation rules by reference to the dedicated policy, not a conflicting duplicate policy;
- acceptable use / no abuse of reservations, scanner, APIs, or AI assistant;
- AI assistant is assistive and cannot bypass server rules or create payment autonomously;
- intellectual property/open-source/repository wording only where accurate;
- suspension/availability/disclaimer wording suitable for a portfolio demo and not an invented SLA;
- governing-law/jurisdiction language only after the operator deployment context is confirmed.

Do not add a registration acceptance checkbox unless it has real product/legal meaning. If explicit Terms acceptance is introduced, it must have versioning/evidence semantics and be scoped as real data processing rather than a cosmetic checkbox.

---

## 12. Security / Trust Contract — `/legal/security`

Create:

- `[NEW]` `frontend/src/app/features/public/legal/security/security.component.ts`
- `[NEW]` `frontend/src/app/features/public/legal/security/security.component.html`
- `[NEW]` focused tests
- `[MODIFY]` route entry

Allowed high-level claims must be backed by code/config, for example:

- Supabase/OIDC/JWT-based authentication where current;
- role-based authorization for protected customer/staff/admin surfaces;
- server-side booking invariants;
- Stripe-hosted/client payment collection boundary where current;
- signed/secure guest ticket access model where current;
- rate limiting/security controls where actually deployed;
- secret/config separation and HTTPS expectations if deployment verifies them;
- AI tool allow-list / no direct AI database/payment access after Phase 15 if implemented.

Forbidden claims unless independently verified:

```text
ISO 27001 certified
SOC 2 compliant/certified
PCI DSS certified by SeatFlow
GDPR certified
penetration tested
zero trust
99.9% SLA
data stays only in Romania/EU
end-to-end encrypted
```

Never publish:

- API keys;
- JWT/token structure beyond generic technology names;
- Stripe client/payment secrets;
- webhook secrets;
- database/Redis hosts;
- internal actuator details;
- exact rate-limit thresholds when that would materially aid abuse;
- admin/scanner security bypass details.

---

## 13. Retention and Deletion Rules

A privacy notice cannot honestly say "we keep data only as long as necessary" while the application has no documented policy.

This task must inventory existing retention behavior and classify each category:

```text
implemented automatic deletion/expiry
manual/operational retention
provider-controlled retention
indefinite/no cleanup currently implemented
unknown
```

Where no retention cleanup exists, do not invent a 30/90/365-day claim. Either:

1. define and implement a safe retention rule if Phase 16 can do so without breaking booking/accounting/ticket invariants; or
2. disclose criteria honestly for the demo and create an explicit follow-up before any real commercial production deployment.

Short-lived values already specified elsewhere (15-minute holds, AI in-memory TTL, browser-session storage) must be distinguished from durable transaction/history records.

---

## 14. User Rights / Contact Workflow Boundary

P16-004 creates the public contact route. P16-002 defines what the privacy page must link to.

Do not claim an automated GDPR self-service portal unless one exists.

For this portfolio phase, an honest contact-based request workflow is acceptable if:

- a real monitored contact channel is configured before public production use;
- requests for access/deletion/correction are not routed to an invented DPO;
- identity verification is proportionate and does not ask users to email passwords/payment secrets/ticket QR codes;
- page wording does not guarantee response times that have not been operationally established.

---

## 15. Runtime Browser Audit

Perform an actual browser storage/network audit in addition to code search.

Minimum scenarios in a fresh browser profile:

1. signed-out visit to `/`;
2. theme selection/change;
3. email/password registration/login if available;
4. OAuth redirect/login if configured for smoke testing;
5. logout;
6. guest reservation creation and checkout navigation;
7. Stripe payment form load in Test Mode;
8. tax preview with test address;
9. guest ticket access;
10. authenticated profile/tickets;
11. AI assistant open/use after Phase 15 if enabled;
12. Nominatim geocoding use on the relevant page.

For each scenario capture only names/categories/domains/lifetimes needed for the inventory. Never commit raw auth tokens, emails, payment client secrets, session IDs, or private browsing captures.

The audit must inspect:

- Cookies;
- Local Storage;
- Session Storage;
- IndexedDB if used;
- service-worker/cache storage if introduced;
- network domains and third-party requests;
- storage created before/after explicit user actions.

---

## 16. Tests

Mandatory automated/focused tests:

1. `/legal/terms`, `/legal/privacy`, `/legal/cookies`, `/legal/security` are accessible signed-out.
2. All four pages use the P16-001 content shell and render one `h1`.
3. Privacy page includes actual data categories and does not contain known placeholder company/DPO claims.
4. Cookies page covers localStorage and sessionStorage, not only the word "cookies".
5. Cookies page includes `seatflow_theme_mode` and guest reservation storage behavior when those remain implemented.
6. Auth service storage behavior is represented accurately in the inventory/tests without exposing token values.
7. Guest email sessionStorage test confirms authenticated checkout does not create the guest proof entry.
8. Guest proof cleanup behavior is covered for every cleanup path touched by this task.
9. If consent decision is `NOT_REQUIRED_FOR_CURRENT_RUNTIME`, no consent banner/component is rendered merely by visiting the site.
10. If consent decision is `REQUIRED`, non-essential storage/network initialization is blocked before consent and remains blocked after reject/withdraw.
11. Public legal/security pages contain no secret-like runtime values from environment/config.
12. AI disclosure is conditionally accurate for the final P15 runtime; do not claim persistent SeatFlow chat history when P15 still uses in-memory memory.
13. external links are labeled/secure (`rel` where appropriate) and keyboard accessible.
14. frontend unit tests + build pass.

---

## 17. Acceptance Criteria

- [ ] Version-controlled data/storage/provider inventory exists and is based on current source plus runtime evidence.
- [ ] Inventory includes Supabase auth persistence, theme localStorage, and guest email sessionStorage if those behaviors remain.
- [ ] Account, booking, payment/tax, ticket, security/operational, and AI data flows are classified.
- [ ] Supabase, Stripe, Nominatim, Groq-if-enabled, and deployed hosting/email/observability providers are evaluated.
- [ ] `/legal/privacy` contains the applicable GDPR Article 13 transparency information without invented facts.
- [ ] `/legal/cookies` covers cookies and equivalent terminal storage.
- [ ] Consent decision is explicit, evidence-based, and dated.
- [ ] No fake banner is added when there is nothing optional to consent to.
- [ ] If consent is required, non-essential processing is genuinely gated and refusal/withdrawal works.
- [ ] `/legal/terms` matches actual demo/product/domain behavior.
- [ ] `/legal/security` makes only verifiable high-level claims and leaks no sensitive internals.
- [ ] No legal entity, DPO, retention duration, data residency, certification, or provider guarantee is invented.
- [ ] Browser/runtime audit is documented without committing personal tokens/secrets.
- [ ] Signed-out legal routes work, tests pass, and frontend builds.

---

## 18. Manual Owner / Deployment Gates

Before marking the legal copy appropriate for a real public/commercial deployment, the project owner/deployer must confirm:

```text
controller/operator legal identity
contact email/channel
business/non-commercial demo status
country/jurisdiction of operation
actual production hosting provider/region
enabled Supabase configuration/region
enabled Stripe mode and account context
enabled email provider
enabled observability provider(s)
Groq enabled? yes/no and applicable provider settings
retention decisions for durable account/booking/payment/ticket records
whether any optional analytics/marketing SDK was added
```

These are configuration/legal facts, not values an implementation agent should invent.

---

## 19. Verification

```bash
cd frontend
npm test -- --watch=false
npm run build
```

Also complete the runtime browser audit matrix from Section 15 and attach the sanitized findings to the version-controlled inventory.

No default CI test should call Stripe, Supabase OAuth, Nominatim, or Groq with real credentials. Provider behavior tests should use mocks/fakes except for deliberate manual smoke verification.
