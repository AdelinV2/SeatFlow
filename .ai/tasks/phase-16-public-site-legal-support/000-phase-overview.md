# Phase 16 — Public Site Completion, Legal & Support

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Estimated effort:** ~4–6 focused implementation hours  

---

## 1. Outcome

Complete every route currently advertised by the footer and remove silent fallback-to-home behavior for missing informational pages. Make the portfolio deployment feel intentional and transparent.

## 2. Required Pages / Routes

Implement lazy routes/components for:

- `/legal/terms` — Terms & Conditions / demo terms;
- `/legal/privacy` — privacy/GDPR-oriented disclosure appropriate to actual data handled;
- `/legal/tax` — Stripe Test Mode / tax display explanation;
- `/legal/refunds` — exact refund/cancellation policy including 24-hour cutoff;
- `/legal/cookies` — accurate storage/cookie explanation;
- `/legal/security` — high-level security architecture without publishing secrets;
- `/support/faq` — common booking/payment/ticket/refund questions;
- `/support/contact` — demo/support contact path without inventing an organization;
- `/status` — public-friendly platform status using the existing health model where possible;
- `/api-docs` — portfolio API documentation landing/navigation page;
- a real 404/not-found component instead of wildcard redirect to home.

## 3. Content Rules

SeatFlow is not a real commercial company. Content must not fabricate:

- legal entity registration;
- physical headquarters;
- DPO/contact identities not actually provided;
- guaranteed refunds/SLAs beyond implemented demo behavior;
- real payment processing.

Every payment/legal page should clearly identify Stripe Test Mode and portfolio/demo status where relevant.

## 4. Privacy / Cookie Accuracy

Document actual data flows: Supabase Auth identity, application user profile, reservation/ticket data and any relevant operational logging. Avoid claiming zero data collection if the app stores email/reservation records.

Do not add a cookie consent banner unless non-essential cookies/analytics requiring consent are actually introduced. If preferences UI exists, it must control real categories.

## 5. UX / Technical Requirements

- reusable content-page layout/typography component;
- table of contents/anchor navigation for longer pages;
- theme support and responsive typography;
- route titles/meta description where applicable;
- accessible headings and links;
- footer links validated against router configuration;
- no broken internal navigation;
- 404 provides useful links back to Events/Home.

## 6. API Docs Page

This is a portfolio landing page, not necessarily public exposure of every internal actuator/service port. It may describe API domains and link only to intentionally exposed Swagger/OpenAPI endpoints or repository docs.

## 7. Suggested Atomic Tasks

1. `001-content-page-shell-routing-and-404.md`
2. `002-terms-privacy-cookies-security-pages.md`
3. `003-refund-tax-and-demo-disclosures.md`
4. `004-faq-contact-status-and-api-docs.md`
5. `005-footer-link-metadata-a11y-and-route-tests.md`

## 8. Definition of Done

- [ ] Every current footer destination resolves intentionally.
- [ ] Refund page matches implemented 24h rule.
- [ ] Stripe Test Mode/demo status is transparent.
- [ ] No fake commercial/legal identity is presented.
- [ ] Cookie UI matches actual behavior.
- [ ] Unknown routes show a real 404.
- [ ] Pages work in dark/light themes and on mobile.
