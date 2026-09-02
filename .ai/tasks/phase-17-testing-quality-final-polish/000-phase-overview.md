# Phase 17 — Full Testing, Quality Gates & Final Polish

**Status:** `PLANNED`  
**Architecture:** `.ai/architecture/09-post-mvp-evolution.md`  
**Estimated effort:** ~12–16 focused implementation hours  

---

## 1. Outcome

Freeze major feature development and perform the final cross-service validation pass against the complete SeatFlow product after Phases 11–16.

This phase does not excuse earlier phases from focused tests. It adds the system-wide, concurrency, integration and E2E confidence that is most valuable once the domain model has stopped moving.

## 2. Test Inventory Audit

Before adding tests:

- inventory existing backend unit/integration tests per service;
- inventory frontend specs/integration tests;
- identify skipped/disabled tests;
- map critical business invariants to at least one deterministic automated test;
- remove obsolete tests tied to superseded single-event-date or legacy layout assumptions.

## 3. Backend Integration Tests

Use Testcontainers/dynamic properties for realistic dependencies where appropriate.

Required focus:

- Event Session persistence/lifecycle/migration behavior;
- Reservation concurrent acquisition for same `(sessionId, seatId)`;
- same seat independently reservable across different sessions;
- 10-seat limit;
- 15-minute expiration and release;
- idempotent reservation/payment requests;
- Stripe webhook/refund idempotency using controlled test doubles/test mode boundaries;
- 24h refund cutoff edge cases;
- refund failure recovery;
- ticket generation/revocation/scanner behavior;
- Outbox publisher retries and duplicate Kafka delivery;
- Analytics projection deduplication/eventual consistency;
- AI tool authorization/confirmation boundary;
- JWT role mapping USER/STAFF/ADMIN.

## 4. Contract / API Tests

Validate critical request/response/event schemas so frontend/services cannot silently drift:

- event/session APIs;
- availability/reservation API;
- payment/refund API;
- ticket/scanner API;
- analytics endpoints;
- AI structured tool responses;
- Kafka event envelope/version fields.

## 5. Frontend Tests

Prioritize behavior over shallow component snapshots:

- session selection changes seat inventory source;
- advanced layout rendering/editor save serialization;
- refund button eligibility display + backend rejection handling;
- revoked ticket UI;
- analytics filters/loading/error;
- AI explicit confirmation UI;
- all legal/support/footer routes;
- accessibility of critical controls.

## 6. Playwright E2E Suite

Keep E2E small and high-value:

### Flow A — Complete purchase
register/login -> browse -> choose event/session -> choose seats -> reserve -> Stripe Test checkout -> confirmation -> My Tickets.

### Flow B — Eligible refund
purchase -> refund while >=24h -> final refunded state -> ticket revoked -> seat available again -> scanner rejects old QR.

### Flow C — Ineligible refund
purchase/session fixture inside 24h -> refund rejected with clear policy message; ticket remains valid.

### Flow D — Admin content
admin -> create/edit advanced venue -> create event with multiple sessions -> publish -> verify customer visibility -> analytics view.

### Flow E — AI (conditional)
assistant -> request seats under budget -> receives authoritative candidates -> explicit confirm -> reservation created -> normal checkout handoff.

AI E2E may be feature-flagged/stubbed in CI to avoid nondeterministic provider cost while tool contracts remain integration-tested.

## 7. Performance / Reliability Checks

For portfolio-scale targets:

- basic concurrent reservation load around hot seats;
- verify DB connection pools and JVM memory remain sane;
- verify WebSocket reconnect reconciliation;
- ensure analytics lag does not affect checkout;
- restart selected services and validate durable recovery from PostgreSQL/Outbox/Kafka state.

Do not introduce fake high-scale benchmarks that the single-VM deployment is not designed to sustain.

## 8. CI Quality Gates

PR checks should fail on critical failures:

- Maven verify;
- frontend lint/test/build;
- selected Playwright smoke flows;
- Docker image build/config validation;
- dependency/static/security checks where already supported;
- Terraform fmt/validate for infrastructure changes.

Longer E2E may run on explicit workflow/release if CI runtime becomes excessive.

## 9. Final Documentation / Portfolio Polish

- update README feature list to reality;
- current architecture diagram including Analytics/AI optional services;
- screenshots/GIFs of advanced seat designer, checkout/ticket, analytics and AI;
- document demo credentials/roles only if safe;
- describe Stripe Test Mode;
- document local start commands and production URL;
- remove obsolete Entra/single-date/old-phase wording from user-facing docs encountered during the pass.

## 10. Suggested Atomic Tasks

1. `001-test-inventory-and-critical-invariant-matrix.md`
2. `002-testcontainers-session-reservation-concurrency-suite.md`
3. `003-refund-ticket-scanner-integration-suite.md`
4. `004-kafka-outbox-analytics-idempotency-suite.md`
5. `005-frontend-critical-feature-tests.md`
6. `006-playwright-purchase-refund-admin-flows.md`
7. `007-ai-tool-contract-and-confirmation-tests.md`
8. `008-ci-quality-gates-performance-and-recovery.md`
9. `009-readme-architecture-screenshots-final-smoke.md`

## 11. Definition of Done

- [ ] Every critical invariant maps to automated coverage.
- [ ] Session-scoped concurrency is proven.
- [ ] Refund 24h boundary and failure recovery are covered.
- [ ] Refunded tickets cannot pass scanner validation.
- [ ] Analytics duplicate events cannot inflate metrics.
- [ ] AI cannot mutate without confirmation or bypass authorization.
- [ ] High-value E2E flows pass reliably.
- [ ] CI enforces the agreed gates.
- [ ] Production smoke test passes on the deployed demo.
- [ ] README/architecture screenshots accurately represent the final product.
