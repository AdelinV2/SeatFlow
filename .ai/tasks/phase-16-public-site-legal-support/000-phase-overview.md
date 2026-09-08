# Phase 16 — Public Site Completion, Legal & Support

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Estimated effort:** ~8–12 focused implementation hours  

---

## 1. Outcome

Complete every public/legal/support route currently advertised by SeatFlow, replace silent wildcard fallback with a real 404, and make the portfolio deployment transparent about what the application actually does with user data, browser storage, payments, AI, and external providers.

Phase 16 is an engineering/compliance-alignment phase, not a claim that SeatFlow has received legal certification. Public copy must be derived from the implemented system and deployment configuration, and unresolved legal/operator facts must remain explicit configuration/content gates rather than being invented.

---

## 2. Required Pages / Routes

Implement lazy public routes/components for:

- `/legal/terms` — Terms & Conditions / portfolio-demo terms;
- `/legal/privacy` — GDPR-oriented privacy notice based on the verified data inventory;
- `/legal/tax` — Stripe Test Mode / tax display explanation;
- `/legal/refunds` — exact refund/cancellation policy matching implemented domain rules;
- `/legal/cookies` — cookies **and similar browser-storage technologies** disclosure;
- `/legal/security` — high-level security/trust page without publishing secrets or making certification claims;
- `/support/faq` — common booking/payment/ticket/refund/account/AI questions;
- `/support/contact` — honest demo/support contact path without inventing an organization or SLA;
- `/status` — public-friendly platform status using the existing health model where appropriate;
- `/api-docs` — portfolio API documentation landing/navigation page;
- a real 404/not-found component instead of wildcard redirect to home.

All informational routes above must remain accessible to signed-out users.

---

## 3. Verified Privacy / Browser-Storage Baseline

The task specifications for this phase are based on a source audit of `develop` performed on 2026-09-08. Implementation must re-run the inventory because Phase 15 may add AI-provider data flows before Phase 16 starts.

Current relevant findings include:

1. **Supabase Auth browser session persistence**
   - `frontend/src/app/core/auth/auth.service.ts` creates `@supabase/supabase-js` with `persistSession: true`, `autoRefreshToken: true`, and `detectSessionInUrl: true`.
   - Supabase documents that browser sessions are stored in local storage by default when `persistSession` is enabled.
   - These values are authentication/security state and must never be presented as analytics or advertising storage.

2. **Theme preference**
   - `seatflow_theme_mode` is read/written in `localStorage` by the theme service and the startup script.
   - It stores only `dark`, `light`, or `system`, but it is still terminal-device storage and therefore belongs in the storage inventory.

3. **Guest reservation proof**
   - `seatflow:reservation-email:<reservationId>` is written to `sessionStorage` for guest checkout.
   - Its value is the guest customer's email address, so this is both terminal-device storage and personal data.
   - It must be documented, minimized, cleared when no longer needed, and never be treated as anonymous storage.

4. **Application personal/transaction data**
   - Account/profile data can include subject/user ID, email, name, role(s), phone, and timestamps.
   - Guest/authenticated reservation data can include email, name, user ID when present, event/session, seats, prices, status, expiry, and timestamps.
   - Payment records include transaction identifiers, reservation/user linkage, email, amount/tax/net/currency/status/timestamps, and Stripe integration state. Card details are handled through Stripe's client/payment flow and must not be claimed as stored by SeatFlow unless a source/runtime audit proves otherwise.
   - Ticket records include ticket/reservation/payment/user/event/session/seat identifiers, customer email, attendee name when present, price/tax/net, QR/ticket data, status, and timestamps.
   - Operational logs/traces/metrics and security/rate-limit data must be included where they can relate to a user or request.

5. **External providers / disclosures to verify**
   - Supabase Auth;
   - Stripe / Stripe.js and Stripe Tax where enabled;
   - OpenStreetMap Nominatim for geocoding requests;
   - Groq when Phase 15 AI is enabled; P15 specifies bounded in-memory conversation state, but user prompts/tool context are still sent to the configured AI provider and therefore must be represented accurately in the final provider inventory;
   - hosting, email/notification, observability, and other runtime processors actually enabled in the deployed environment.

6. **No marketing analytics baseline**
   - The source audit did not identify a first-party `document.cookie` implementation or an intentional Google Analytics/PostHog/Hotjar-style marketing analytics integration.
   - This is not sufficient by itself to prove that runtime third-party scripts set no cookies/storage. Task P16-002 must include a browser/runtime storage audit before the final cookie-banner decision.

---

## 4. GDPR / ePrivacy Engineering Rule

Do **not** equate "GDPR compliant" with "show a cookie banner".

For the Romanian deployment baseline, task implementation must account for:

- GDPR transparency requirements, including the Article 13 information set for data collected from users;
- Romanian Law 506/2004 Article 4(5), which generally requires prior information and agreement for storing/accessing information on a user's terminal equipment;
- Article 4(6) exemptions for transmission-only storage/access and storage/access strictly necessary to provide an information-society service expressly requested by the user;
- EDPB Guidelines 2/2023, which make clear that Article 5(3) ePrivacy scope is technology-neutral and is not limited to classic HTTP cookies.

Reference sources to verify during implementation:

- `https://eur-lex.europa.eu/eli/reg/2016/679/oj`
- `https://legislatie.just.ro/Public/DetaliiDocument/288598`
- `https://www.edpb.europa.eu/our-work-tools/our-documents/guidelines/guidelines-22023-technical-scope-art-53-eprivacy-directive_en`
- `https://supabase.com/docs/reference/javascript/auth`

### Consent UI decision

- Do **not** add a generic cookie banner merely for appearances.
- First classify every cookie/localStorage/sessionStorage/SDK storage item by provider, purpose, lifetime, whether it contains/relates to personal data, and whether the strictly-necessary exception is defensible for the concrete behavior.
- Authentication/session storage required for a user-requested signed-in session must not be disabled by a generic marketing-consent toggle.
- Guest checkout proof required to continue a user-requested checkout must not be mislabeled as analytics.
- The theme preference requires explicit classification because it is functional personalization; implementation should prefer writing it only after the user has made a theme choice where practical.
- If any non-essential analytics, advertising, profiling, or other non-exempt terminal storage is present, implement a real consent mechanism that blocks it until the required consent is obtained and supports refusal/withdrawal without degrading unrelated core functionality.
- If the verified runtime contains only exempt/strictly-necessary storage, publish the `/legal/cookies` disclosure and **do not** create a pointless consent banner.

---

## 5. Content / Legal Fact Gates

SeatFlow is currently a portfolio/demo system. Content must not fabricate:

- a legal entity, company registration number, VAT number, headquarters, or controller identity not actually supplied;
- DPO/contact identities not actually provided;
- compliance certifications (ISO 27001, SOC 2, PCI certification, etc.);
- DPA/SCC/data-residency guarantees not verified for the deployed providers;
- guaranteed SLA, response times, refunds, availability, or support hours beyond implemented behavior;
- real payment processing when the deployment is using Stripe Test Mode.

Before calling the privacy/terms content production-ready, the project owner/deployer must provide or confirm the controller/operator identity and contact channel, deployment jurisdiction/context, enabled providers, retention decisions, and whether the site is still a non-commercial demo. Until then, wording must clearly identify the deployment as a portfolio/demo and avoid pretending missing legal facts are known.

---

## 6. UX / Technical Requirements

- reusable informational content-page shell/typography;
- optional table of contents/anchor navigation for long pages;
- dark/light/system theme support and responsive typography;
- route titles/meta descriptions;
- accessible headings, landmarks, focus handling, external-link labeling, and keyboard navigation;
- footer links validated against router configuration;
- no broken internal navigation or silent legal-route fallback;
- real 404 page with Home/Events navigation;
- legal/support pages must not require authentication;
- no secrets, internal service addresses, raw health payloads, payment client secrets, JWT details, or sensitive configuration on public pages.

---

## 7. API Docs / Status Boundaries

`/api-docs` is a portfolio landing page, not automatic public exposure of every internal actuator, Swagger endpoint, service port, or admin API.

`/status` is a sanitized public view. It must never proxy or dump internal health details that expose database hosts, dependency topology, credentials, stack traces, build secrets, or private service names unless those names are deliberately public.

---

## 8. Atomic Tasks

1. `001-content-page-shell-routing-and-404.md`
2. `002-terms-privacy-cookies-security-pages.md`
3. `003-refund-tax-and-demo-disclosures.md`
4. `004-faq-contact-status-and-api-docs.md`
5. `005-footer-link-metadata-a11y-and-route-tests.md`

Tasks must be implemented in order. P16-002 owns the privacy/storage/provider inventory and consent decision; later tasks consume that result instead of redefining it.

---

## 9. Definition of Done

- [ ] Every intended footer/public destination resolves intentionally.
- [ ] Unknown routes show a real 404 instead of redirecting to `/`.
- [ ] Privacy notice describes the **actual** data categories, purposes, providers, retention rules/criteria, legal bases, rights/contact path, and international-transfer facts that are verified for the deployment.
- [ ] Cookies/storage page includes cookies and similar technologies, including localStorage/sessionStorage.
- [ ] Runtime browser audit confirms which storage items are created before and after authentication, guest checkout, Stripe interaction, theme selection, and AI use.
- [ ] Consent UI exists only if non-exempt storage actually requires it; if it exists, refusal and withdrawal are functional and non-essential storage is blocked before consent.
- [ ] Refund page matches implemented cancellation/refund rules.
- [ ] Stripe Test Mode/demo status is transparent where applicable.
- [ ] No fake commercial/legal identity, certification, SLA, or processor guarantee is presented.
- [ ] AI/Groq disclosure is accurate if Phase 15 is enabled, including the fact that chat content is provider-processed even though SeatFlow does not create a persistent AI chat database in P15.
- [ ] Public status/API-doc pages expose only intentionally public information.
- [ ] Pages work in dark/light themes, on mobile, and with keyboard navigation.
- [ ] Focused route/content/storage tests are present; Phase 17 retains ownership of the final full-system regression gate.
