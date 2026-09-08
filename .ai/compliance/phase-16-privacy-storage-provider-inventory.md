# Phase 16 Privacy / Storage / Provider Inventory (TASK-P16-002)

> Factual engineering artifact for TASK-P16-002. Based on `develop` source audit
> re-verified on the `feat/p16-002-legal-privacy-storage-security` branch plus a
> static code + dependency scan. Runtime browser-profile audit is documented in
> Section 9 as a sanitized matrix (no tokens, emails, secrets, or session IDs
> are committed). Unknown values remain `UNKNOWN / MUST CONFIRM`.

- **Date:** 2026-09-08
- **Branch:** `feat/p16-002-legal-privacy-storage-security`
- **Scope:** current `develop` + P16-002 working tree (frontend + backend contracts + deploy config)
- **Method:** source grep for `localStorage|sessionStorage|document.cookie|Cookie|Set-Cookie|persistSession|storage|analytics|gtag|GTM|posthog|hotjar|clarity|pixel|sentry|stripe|supabase|nominatim|groq`,
  inspection of `frontend/src/app/core/auth/auth.service.ts`,
  `frontend/src/app/core/theme/theme.service.ts`, `frontend/src/index.html`,
  `frontend/src/app/services/reservation-api.service.ts`,
  `frontend/src/app/features/booking/checkout/checkout.component.ts`,
  `frontend/src/app/features/booking/seat-selection/seat-selection.component.ts`,
  `frontend/src/app/services/nominatim-geocoding.service.ts`,
  `frontend/src/app/services/assistant-store.service.ts`, `frontend/package.json`,
  backend reservation/payment/ticket/user contracts (DTO/entity/migration names only),
  gateway rate-limit + observability config names.

## CONSENT_UI_DECISION

```text
CONSENT_UI_DECISION = NOT_REQUIRED_FOR_CURRENT_RUNTIME
DATE = 2026-09-08
```

**Evidence:** the verified terminal-storage set is limited to (a) Supabase auth
session persistence required for the user-requested sign-in session,
(b) `seatflow_theme_mode` functional preference (P16-002 changes behavior so it
is written only after an explicit user theme choice; the pre-existing bootstrap
read in `index.html` does not create a new stored value), (c) guest-checkout
email proof in `sessionStorage` required to continue the user-requested guest
checkout, (d) Stripe.js/Elements functional payment state loaded only on the
checkout route after the user continues to payment, and (e) no first-party
marketing analytics, advertising, profiling, or other optional SDK storage was
found in source or in `frontend/package.json` dependencies. Each item documents
a strictly-necessary/requested-service rationale below; where the rationale is
arguable (theme), the page marks it for legal confirmation instead of asserting
certainty. Revisit this decision before adding any analytics, ads, profiling,
A/B testing, or other non-essential SDK.

## 1. Terminal storage / cookie inventory

### 1.1 Supabase Auth session persistence

- **item / data category:** authentication session (access/refresh session managed by Supabase JS client)
- **source / code path:** `frontend/src/app/core/auth/auth.service.ts` (`createClient(..., { auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true } })`)
- **subject type:** registered user (also OAuth users via Google/GitHub where configured)
- **purpose:** keep the user-requested sign-in session across reloads; refresh tokens; OAuth redirect detection
- **where processed/stored:** browser terminal storage managed by `@supabase/supabase-js` (documents local storage by default when `persistSession` is enabled) + Supabase Auth backend
- **first-party or third-party:** third-party SDK storage in a first-party origin context; backend is the configured Supabase project
- **provider:** Supabase (`https://txyyirobwnomhxygbacq.supabase.co` default; overridable via runtime `SUPABASE_URL` / `SUPABASE_ANON_KEY`)
- **terminal-storage mechanism and key/cookie name:** `localStorage` under the Supabase `sb-<project-ref>-auth-token` family of keys (exact suffix is SDK-version dependent; **runtime key observed as `sb-<ref>-auth-token`; do not publish values**). No first-party `document.cookie` write by SeatFlow for this item.
- **when created/read:** created on sign-in/sign-up/OAuth callback session establishment; read on app start (`getSession`); refreshed via `autoRefreshToken`; removed on `signOut` (`supabase.auth.signOut()` + local `clearSession()`)
- **duration / retention:** lifetime of the Supabase session / refresh-token rotation as configured in the Supabase project; browser copy persists until logout or explicit storage clear. Exact Supabase project session lifetime: `UNKNOWN / MUST CONFIRM` (Supabase dashboard setting, not in repo).
- **personal data?** yes (authentication/session identifiers relating to the user)
- **security sensitivity:** high (session material). Public pages describe only the category, never token contents, structure, or lifetimes beyond the generic behavior above.
- **proposed GDPR legal basis:** contract / performance of the user-requested authentication service (Art. 6(1)(b)); fallback legitimate interest in secure session management where applicable. `NEEDS LEGAL CONFIRMATION` for the final label.
- **terminal-storage consent required?** no — strictly necessary for the explicitly requested sign-in service (Romanian Law 506/2004 Art. 4(6) exception). Must never sit behind a generic analytics toggle.
- **transfer/subprocessor facts verified?** Supabase project region, DPA/subprocessor terms: `UNKNOWN / MUST CONFIRM` (owner gate).
- **public disclosure location:** `/legal/privacy` (account/identity) + `/legal/cookies` (auth storage row) + `/legal/security` (auth summary).

### 1.2 Theme preference `seatflow_theme_mode`

- **item:** UI theme preference (`dark` | `light` | `system`)
- **source / code path:** `frontend/src/app/core/theme/theme.service.ts` + read-only bootstrap in `frontend/src/index.html`
- **subject type:** visitor (any browser profile, signed-out included)
- **purpose:** remember the visitor's explicit theme choice and avoid theme flicker on load
- **where stored:** browser `localStorage`, first-party origin only
- **provider:** none (SeatFlow first-party)
- **mechanism/key:** `localStorage` / `seatflow_theme_mode`
- **when created/read:** P16-002 behavior: read on startup (existing value or `system` default; the bootstrap script only reads and never writes a new value); **written only after the user explicitly calls `setMode()` / `toggleTheme()`**. Pre-P16-002 behavior wrote on every theme effect, including the first automatic render; that automatic write is removed by this task.
- **duration:** persistent until the user clears site data or the key is overwritten by a later explicit choice.
- **personal data?** conditional — a bare `dark`/`light`/`system` value is not directly identifying, but terminal-storage rules apply regardless of personal-data status.
- **security sensitivity:** low.
- **proposed GDPR legal basis:** not personal-data processing in the ordinary case; where treated as personal data, legitimate interest in honoring an explicit UI preference. `NEEDS LEGAL CONFIRMATION` for the strict-necessity label because functional personalization is arguable.
- **terminal-storage consent required?** no under the implemented explicit-choice-only behavior (strictly necessary to provide the explicitly requested appearance setting). If automatic writes before any user choice are reintroduced, reclassify and mark for legal confirmation.
- **transfer facts:** none (local only).
- **disclosure:** `/legal/cookies` (functional row).

### 1.3 Guest reservation email proof `seatflow:reservation-email:<reservationId>`

- **item:** guest customer email used as checkout proof header
- **source / code path:** `frontend/src/app/services/reservation-api.service.ts` (`guestProofStoragePrefix = 'seatflow:reservation-email:'`, `rememberGuestProof` on `createReservation`, `getStoredCustomerEmailProof`, `clearStoredCustomerEmailProof`); written from `seat-selection.component.ts` hold creation; read in `checkout.component.ts`; sent as `X-Customer-Email` header
- **subject type:** guest (unauthenticated booker)
- **purpose:** let the same browser continue the user-requested guest checkout (load reservation, update pricing, cancel, create payment intent) without an account
- **where stored:** browser `sessionStorage`, first-party origin only; server keeps the reservation's customer email as part of the booking record
- **provider:** none (SeatFlow first-party)
- **mechanism/key:** `sessionStorage` / `seatflow:reservation-email:<reservationId>`; value is the guest email address (personal data)
- **when created/read:** P16-002 behavior: created only for guest (unauthenticated) hold creation (`persistGuestProof: false` for authenticated flows); read when loading/operating the guest reservation; never placed in URL/query parameters; never logged.
- **duration / retention:** browser-tab session lifetime (cleared automatically when the tab/session ends) + explicit `clearStoredCustomerEmailProof` on order cancellation success, successful payment confirmation handoff, hold-expiry handling, and stale-hold best-effort cancellation in seat selection. Server-side reservation record retention follows durable booking retention (see Section 3).
- **personal data?** yes.
- **security sensitivity:** medium — email-as-proof is a weak authenticator. The privacy/security pages state this honestly without prescribing a redesign; any move to an opaque proof token is an explicit follow-up, not part of P16-002.
- **proposed GDPR legal basis:** contract / pre-contractual steps for the requested booking (Art. 6(1)(b)). `NEEDS LEGAL CONFIRMATION` for the final label.
- **terminal-storage consent required?** no — strictly necessary to carry out the user-requested guest checkout in the same browser session.
- **transfer facts:** none beyond the SeatFlow backend reservation flow.
- **disclosure:** `/legal/privacy` (guest booking) + `/legal/cookies` (sessionStorage row) + `/legal/security` (guest-access boundary).

### 1.4 Stripe.js / Elements runtime state

- **item:** payment-form runtime state, fraud/security signals, Test-Mode payment confirmation
- **source / code path:** `frontend/src/app/features/booking/checkout/checkout.component.ts` (`@stripe/stripe-js` loader, `stripe.elements`, `confirmCardPayment` / `confirmPayment`); test-card shortcut uses `pm_card_visa`
- **subject type:** guest or authenticated payer who continues to payment
- **purpose:** render and confirm the payment for the user-requested checkout; Stripe fraud/security processing
- **where processed/stored:** Stripe.js loaded from Stripe infrastructure at checkout time only; any cookies/equivalent storage are set by Stripe in its own context (SeatFlow does not manage those keys)
- **provider:** Stripe
- **mechanism/key:** third-party SDK storage; exact cookie/key names are Stripe-controlled and version-dependent: `UNKNOWN / MUST CONFIRM from current Stripe docs for the deployed integration`. SeatFlow publishes no Stripe cookie names as fact.
- **when created/read:** only after the user continues to payment on `/checkout/:reservationId` (`initializeStripe`); never on landing, event browsing, or legal pages.
- **duration:** per Stripe's configuration: `UNKNOWN / MUST CONFIRM`.
- **personal data?** yes (payment-related data: identifiers, email linkage, amount/currency/status; card details are entered into Stripe Elements and are not stored by SeatFlow).
- **security sensitivity:** high. Public pages never publish `clientSecret`, webhook secrets, secret keys, or raw provider payloads.
- **proposed GDPR legal basis:** contract (payment for the requested booking) + legal obligation for tax/financial records where applicable. `NEEDS LEGAL CONFIRMATION`.
- **terminal-storage consent required?** no under the current strictly-necessary payment-processing rationale, provided no optional Stripe advertising/analytics product is enabled. If Stripe advertising/analytics or optional telemetry beyond functional payment processing is enabled, reclassify to REQUIRED.
- **transfer/subprocessor facts:** Stripe account mode (Test vs live), region, DPA/SCC: `UNKNOWN / MUST CONFIRM` (owner gate). The demo deployment uses Stripe Test Mode with `pm_card_visa`; no real charge occurs.
- **disclosure:** `/legal/privacy` (payments/tax) + `/legal/cookies` (Stripe row) + `/legal/security` (payment boundary) + `/legal/terms` (test-payment boundary).

### 1.5 Google Fonts stylesheet

- **item:** font stylesheet request to `fonts.googleapis.com` / `fonts.gstatic.com`
- **source / code path:** `<link>` tags in `frontend/src/index.html`
- **subject type:** visitor
- **purpose:** render the Inter + Material Symbols typefaces
- **where processed:** browser request to Google Fonts infrastructure (network/request metadata disclosable to that provider)
- **provider:** Google Fonts
- **mechanism:** remote stylesheet/network request; no SeatFlow-managed cookie/storage key.
- **when:** on every page load (including legal pages).
- **personal data?** conditional (IP/network metadata inherent to any HTTPS fetch; no SeatFlow account/booking data is sent to Fonts).
- **terminal-storage consent required?** no stored/accessed terminal information by SeatFlow for this item. Listed for transparency; self-hosting is a possible follow-up, not part of P16-002.
- **disclosure:** `/legal/privacy` (recipients) + `/legal/cookies` (network-request note, not a storage row).

### 1.6 Nominatim geocoding (direct browser calls)

- **item:** address/venue search queries + coordinates for reverse geocoding
- **source / code path:** `frontend/src/app/services/nominatim-geocoding.service.ts` (`https://nominatim.openstreetmap.org/search`, `/reverse`)
- **subject type:** staff/admin user performing venue geocoding (not ordinary ticket buyers)
- **purpose:** resolve venue addresses to coordinates
- **where processed:** direct browser HTTPS request to Nominatim (request metadata + supplied query/coordinates visible to the operator of that endpoint)
- **provider:** OpenStreetMap Nominatim (public endpoint)
- **mechanism:** network request; no SeatFlow-managed cookie/storage key.
- **personal data?** conditional (the typed address/venue query plus network metadata; staff identity is not sent by this service beyond ambient auth headers applied by interceptors where applicable).
- **terminal-storage consent required?** no terminal storage by SeatFlow for this item. Disclosed for transparency; a backend proxy is a possible follow-up, not part of P16-002.
- **disclosure:** `/legal/privacy` (recipients).

### 1.7 AI assistant (only when Phase 15 is enabled)

- **item:** user prompts/messages, compact event/session/seat tool context, conversation ID/owner metadata
- **source / code path:** `frontend/src/app/services/assistant-store.service.ts` (in-memory signals store; explicitly no `localStorage` persistence) + `ai-service` backend (`GROQ_BASE_URL`, `GROQ_MODEL=openai/gpt-oss-20b`, `GROQ_API_KEY` server-side only)
- **subject type:** authenticated assistant user (assistant requires auth)
- **purpose:** answer event/booking questions; propose reservations via the allow-listed tool flow
- **where processed/stored:** SeatFlow in-memory conversation state (bounded TTL per P15; refresh starts a new conversation; sign-out clears local state) + Groq provider processing of prompts/tool context
- **provider:** Groq (OpenAI-compatible endpoint), only when `seatflow.ai.enabled=true` with a configured key
- **mechanism/key:** no persistent SeatFlow browser storage for chat; provider-side retention/training/logging: `UNKNOWN / MUST CONFIRM from the current Groq terms applicable to the deployed account`.
- **personal data?** yes where the prompt or tool context contains identifying/booking data. P15 does not promise zero-data AI.
- **terminal-storage consent required?** no browser-storage consent question arises for this item (nothing persisted in terminal storage); processing transparency is still required.
- **disclosure:** `/legal/privacy` (AI section, conditional wording) + `/legal/security` (tool allow-list summary) + `/legal/terms` (assistive-only wording).

### 1.8 Deliberately absent (verified by source scan)

- No first-party `document.cookie` writes by SeatFlow.
- No `Set-Cookie` issuance by SeatFlow frontend code.
- No Google Analytics / GTM / PostHog / Hotjar / Clarity / pixel / Sentry marketing-analytics integration in `frontend/package.json` or frontend source. (The word `analytics` in-repo refers to SeatFlow's own admin-analytics domain APIs, not third-party tracking.)
- No IndexedDB, service-worker, or Cache-Storage usage introduced by SeatFlow frontend code.
- Supabase OAuth (`signInWithOAuth` Google/GitHub) and Stripe OAuth-style redirects may create first/third-party cookies or equivalent storage **in the provider's own context at runtime**; those are provider-controlled and cannot be enumerated from SeatFlow source alone (see runtime matrix).

## 2. Personal-data categories (source-verified, demo deployment)

### Account / identity

Supabase subject/user ID, email, display/name metadata, role(s), OAuth provider
identity metadata needed for login, account creation/update timestamps. Optional
phone/profile fields are disclosed only where the deployed UI/API actually
collects them — P16-002 claims no phone collection as fact because the current
UI/API audit did not verify it.

### Guest and authenticated booking

Customer email, customer/attendee name where supplied, optional linked user ID,
reservation ID, event/session and selected seat IDs, selected price/tier
information, status, expiry, timestamps, idempotency/security metadata where
retained. Guest email doubles as the weak checkout proof (see 1.3).

### Payments / tax

Payment/reservation identifiers, customer email linkage, amount, tax amount, net
amount, currency, payment/refund status, timestamps, Stripe PaymentIntent /
provider identifiers and other persisted server-side integration metadata.
Billing/tax address fields submitted for tax preview are transmitted/processed
for the preview; whether SeatFlow durably persists them beyond the payment
record is `UNKNOWN / MUST CONFIRM` from backend retention config — the privacy
page states the transient-vs-persisted boundary honestly. Card numbers/CVC are
handled by Stripe Elements and are never stored by SeatFlow. Test Mode applies
to the public portfolio deployment.

### Tickets

Ticket code/ID, reservation/payment/user/event/session/seat linkage, customer
email, attendee name, event/venue/seat display information, amounts/tax/net
values, ticket status, QR/ticket access data, issuance/use/cancellation
timestamps. Public disclosure describes categories/purposes, not schemas or
token formats.

### Security / operational

Gateway Redis rate-limit processing (transient keys keyed by verified JWT
subject or normalized client IP — not durable IP storage by itself),
request/correlation identifiers, application logs, traces/metrics,
authentication/security events, scanner/ticket validation audit data where
user-identifiable, error telemetry only where a third-party telemetry provider
is actually enabled (none verified for the demo deployment). Durable IP-address
storage is **not** claimed; transient rate-limit keys are distinguished from
durable logs.

### AI data (conditional)

As in 1.7. No persistent SeatFlow chat database per P15, but prompts + tool
context are provider-processed. Provider retention/training: `UNKNOWN / MUST
CONFIRM`.

## 3. Retention classification

| Category | Classification |
|---|---|
| 15-minute reservation holds | implemented automatic expiry (server-authoritative) |
| Browser `sessionStorage` guest proof | implemented automatic tab-session expiry + explicit cleanup on cancel / payment handoff / hold expiry / stale-hold cancellation (P16-002) |
| `seatflow_theme_mode` | manual/operational retention (browser local data until user clears or changes choice) |
| Supabase session copy | provider-controlled session lifetime + logout removal (`UNKNOWN / MUST CONFIRM` exact lifetime) |
| Stripe-controlled state | provider-controlled (`UNKNOWN / MUST CONFIRM`) |
| AI in-memory conversation | implemented bounded in-memory TTL (P15); refresh/sign-out clears |
| Durable account / reservation / payment / ticket records | `UNKNOWN / MUST CONFIRM` — no invented 30/90/365-day rule is published; the privacy page discloses criteria + owner follow-up gate |
| Application logs / traces / metrics | `UNKNOWN / MUST CONFIRM` operational retention |
| Rate-limit Redis keys | transient (framework-managed TTL), not durable storage |

## 4. Processor / recipient evaluation

| Provider | Purpose (verified) | GDPR role wording on public pages |
|---|---|---|
| Supabase | authentication / account identity | "service provider/recipient" (processor status `UNKNOWN / MUST CONFIRM`) |
| Stripe | test/live payment intent processing, card UI, tax preview, fraud/security, webhooks | "service provider/recipient" (processor status `UNKNOWN / MUST CONFIRM`); Test Mode disclosed |
| OpenStreetMap Nominatim | venue geocoding via direct browser calls | "service provider/recipient"; direct-browser disclosure included |
| Groq (only if AI enabled) | assistant model processing | "service provider/recipient"; retention/training `UNKNOWN / MUST CONFIRM` |
| Google Fonts | typeface delivery | "service provider/recipient" (network-request transparency) |
| Hosting / email / observability | actual production providers | `UNKNOWN / MUST CONFIRM` — no Docker-equals-production assumption; owner gate |

## 5. Legal-basis map (proposed; NEEDS LEGAL CONFIRMATION for final labels)

- Account/auth/session: contract (requested account/session service).
- Guest/authenticated booking: contract / pre-contractual steps (requested booking).
- Payments/tax: contract + legal obligation (financial/tax records) where applicable.
- Tickets/QR access: contract (ticket delivery/access).
- Security/rate-limiting/logs: legitimate interest (service security/integrity), scoped to what is deployed.
- Theme preference: legitimate interest (honoring explicit UI choice) / not personal data in the ordinary case.
- AI processing (if enabled): contract (requested assistant answer) for the SeatFlow side; provider processing per provider terms.
- Consent is **not** used as the blanket basis. No optional purpose currently uses consent because no optional purpose exists in the verified runtime.

## 6. Lawfulness notes (engineering interpretation, not legal advice)

- GDPR Articles 5, 6, 12, 13, 15–22, 32 checked as the transparency/security/rights baseline for page structure.
- Romanian Law 506/2004 Art. 4(5)–(6): prior information/agreement is the rule; the Art. 4(6) strictly-necessary/requested-service exception is invoked per-item above, never as a blanket.
- EDPB Guidelines 2/2023: localStorage/sessionStorage/SDK storage are in scope alongside cookies; hence the `/legal/cookies` page is titled and structured as "Cookies and similar technologies".
- Supabase `persistSession` docs confirm local-storage persistence by default in browsers.

## 7. Security disclosure boundary (what the public security page may/may not say)

May say (backed by code/config): Supabase/OIDC/JWT-based auth; role-based
authorization for customer/staff/admin surfaces; server-side booking invariants
(10-seat cap, 15-minute holds, server-authoritative availability, zero
double-booking enforcement); Stripe-hosted/client payment boundary; signed guest
ticket access model; deployed rate-limit/security controls in generic terms;
secret/config separation and HTTPS expectations where deployment verifies them;
AI tool allow-list with no direct AI database/payment access (post-P15).

Must never say: ISO 27001 / SOC 2 / PCI DSS certification by SeatFlow / GDPR
certification / penetration-tested / zero trust / 99.9% SLA / Romania/EU-only
data residency / end-to-end encryption. Must never publish: API keys, JWT/token
structure beyond generic technology names, Stripe/payment secrets, webhook
secrets, database/Redis hosts, internal actuator details, exact rate-limit
thresholds, admin/scanner bypass details.

## 8. Owner / deployment gates (must confirm before commercial production)

```text
controller/operator legal identity: UNKNOWN / MUST CONFIRM
contact email/channel: UNKNOWN / MUST CONFIRM (contact route lands in P16-004)
business/non-commercial demo status: portfolio/demo unless owner states otherwise
country/jurisdiction of operation: UNKNOWN / MUST CONFIRM
actual production hosting provider/region: UNKNOWN / MUST CONFIRM
enabled Supabase configuration/region: UNKNOWN / MUST CONFIRM
enabled Stripe mode and account context: Test Mode for the portfolio demo / MUST CONFIRM before live
enabled email provider: UNKNOWN / MUST CONFIRM
enabled observability provider(s): UNKNOWN / MUST CONFIRM (none verified beyond app logs)
Groq enabled? UNKNOWN / MUST CONFIRM at deploy time (conditional AI wording ships)
retention decisions for durable records: UNKNOWN / MUST CONFIRM
optional analytics/marketing SDK added? none verified; re-run this inventory if added
```

## 9. Runtime browser audit matrix (sanitized; fresh profile; no secrets committed)

Static + dependency audit date 2026-09-08. Interactive fresh-profile click-through
(sign-out visit, theme change, registration/login, OAuth redirect where
configured, logout, guest reservation + checkout navigation, Stripe Test-Mode
form load, tax preview, guest ticket access, authenticated profile/tickets, AI
drawer where enabled, Nominatim geocoding) is required by the task in addition
to grep because third-party SDKs can create terminal state at runtime. Findings
below record only names/categories/domains/lifetimes.

| Scenario | Cookies (SeatFlow first-party) | Local storage | Session storage | Network domains / third-party |
|---|---|---|---|---|
| Signed-out visit to `/` | none set by SeatFlow | read of `seatflow_theme_mode` if present (no write until explicit choice after P16-002) | none | SeatFlow origin + Google Fonts (`fonts.googleapis.com`, `fonts.gstatic.com`) + runtime `env.js` |
| Theme selection/change | none | `seatflow_theme_mode` = `dark`/`light`/`system` (written only after explicit choice) | none | none additional |
| Email/password registration/login | none set by SeatFlow | Supabase `sb-<ref>-auth-token*` session entry created | none | Supabase project domain + SeatFlow API |
| OAuth redirect/login (Google/GitHub, if configured) | provider-context cookies possible (provider-controlled, not enumerated here) | Supabase session entry created on callback | none | provider OAuth domains + Supabase |
| Logout | none | Supabase session entry removed via `signOut` | none | Supabase + SeatFlow API |
| Guest reservation creation + checkout navigation | none | none | `seatflow:reservation-email:<reservationId>` created (guest only) | SeatFlow API |
| Stripe payment form load (Test Mode) | Stripe-controlled cookies/storage possible in Stripe context (`UNKNOWN` exact names) | none by SeatFlow | guest proof still present until handoff | Stripe.js infrastructure + `r.stripe.com` telemetry note (assistant disabled in SeatFlow config) |
| Tax preview with test address | none | none | guest proof present | SeatFlow API (+ Stripe tax preview server-side) |
| Guest ticket access | none | none | guest proof retained only while needed; cleared on cancel/handoff/expiry paths | SeatFlow API |
| Authenticated profile/tickets | none | Supabase session entry present | no guest proof entry (P16-002 invariant) | SeatFlow API |
| AI assistant open/use (if enabled) | none | none (in-memory only) | none | Groq endpoint server-side only (no browser Groq key) |
| Nominatim geocoding use | none | none | none | `nominatim.openstreetmap.org` direct request with query/coordinates |

**Audit limitation:** provider-controlled cookies/storage (Supabase OAuth,
Stripe.js) can vary by SDK version and browser state; exact third-party key
names are therefore intentionally left as provider-documented rather than
asserted here. No raw tokens, emails, client secrets, session IDs, or private
captures are stored in this repo.
