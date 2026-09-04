# Workflow 05: Code Review Protocol

**Role:** Independent Reviewer / Quality Gate

---

## 1. Goal

Perform a **proactive, evidence-based independent review** of a completed implementation to discover realistic bugs, invariant violations, security issues, integration mismatches, migration problems, test gaps, and meaningful maintainability/performance risks before task finalization.

Review the diff first, but inspect surrounding code/contracts whenever correctness depends on them.

Model/provider/effort selection is governed only by `.ai/MODEL_ROUTER.md`.

---

## 2. Reviewer Independence

Approach the patch as if another engineer wrote it.

Do not assume correctness because:

- implementation followed the task;
- tests are green;
- code looks idiomatic;
- implementer/model is strong;
- a similar pattern exists elsewhere.

For substantive work, the implementation agent must not be its own final approval authority.

Prefer a different model family/provider when practical. Critical SeatFlow domains use the stronger review route from `.ai/MODEL_ROUTER.md`.

---

## 3. Review Inputs

Gather before judging:

1. task file + acceptance criteria + orchestration metadata;
2. relevant ADRs/architecture docs;
3. root/subsystem `AGENTS.md`;
4. complete branch/PR diff against the correct base;
5. new/modified tests;
6. implementation self-verification evidence;
7. neighboring call sites/contracts needed for correctness;
8. relevant migrations/API/event/frontend models.

Do not review only the changed source file when behavior depends on other components.

---

## 4. Review Order

### 4.1 Task / contract compliance

Verify:

- every acceptance criterion;
- exact DTO/API/event/schema contracts;
- required state transitions;
- accepted ADR decisions;
- repository standards;
- no unapproved scope expansion.

A polished implementation of the wrong contract is still wrong.

### 4.2 Correctness / edge cases

Search actively for:

- wrong/missing conditions;
- off-by-one/boundary errors;
- null/empty/missing state;
- invalid transitions;
- time/time-zone mistakes;
- money precision/rounding mistakes;
- wrong exception/status mapping;
- stale state/lost updates;
- partial-success paths;
- retry/replay/reconnect failures;
- cleanup/resource leaks;
- unenforced assumptions.

Construct a concrete failure scenario before reporting a finding.

### 4.3 SeatFlow critical invariants

When relevant verify explicitly:

- max 10 seats/reservation;
- strict 15-minute hold semantics;
- zero double booking under concurrency;
- PostgreSQL source of truth;
- transactional outbox semantics;
- reservation/payment/refund idempotency;
- payment/refund/webhook transitions;
- server-side auth/authorization;
- distributed consistency/order assumptions;
- migration/data-integrity safety.

Application-level checks are insufficient when atomic DB enforcement is required.

### 4.4 Concurrency / distributed behavior

Look for:

- check-then-act/TOCTOU;
- missing/wrong transaction boundaries;
- inconsistent lock ordering;
- stale reads;
- optimistic locking without correct retry semantics;
- duplicate event processing;
- non-idempotent side effects;
- unsafe retries;
- out-of-order assumptions;
- external-call + DB partial-failure windows;
- cache treated as authority;
- client/server reconnect divergence.

### 4.5 Security / privacy

Inspect:

- authentication/role checks;
- ownership/IDOR;
- input validation at trust boundaries;
- mass assignment/over-posting;
- secret/token exposure;
- sensitive logging;
- webhook verification;
- privilege escalation through alternate endpoints/state transitions;
- frontend-only enforcement of backend rules.

### 4.6 Database / migrations

Inspect:

- entity <-> DDL alignment;
- PK/FK/unique/check/index constraints;
- nullability/defaults;
- cascade/delete/soft-delete semantics;
- query correctness/N+1 where material;
- migration order/compatibility/backfill assumptions;
- PostgreSQL lock/query behavior;
- destructive/irreversible changes.

### 4.7 API / messaging / integration

Inspect:

- HTTP method/path/status/body;
- validation/error envelope;
- backend DTO <-> frontend model alignment;
- event topic/payload/envelope;
- serialization/deserialization;
- backward compatibility;
- retry/idempotency across service boundaries;
- Eureka/LoadBalancer/shared-client requirements.

### 4.8 Tests

Review tests as critically as production code:

- missing negative/boundary cases;
- weak assertions;
- changed branch never exercised;
- mocks encoding expected outcome;
- missing regression coverage;
- integration behavior tested only as unit;
- fake concurrency without overlap;
- flaky timing assumptions;
- missing auth/idempotency/retry cases;
- frontend async/reconnect/error states untested.

### 4.9 Frontend quality

When applicable inspect:

- Angular Signal/computed state;
- stale/racing HTTP requests;
- optimistic rollback/reconciliation;
- WebSocket reconnect;
- resource cleanup;
- loading/error/empty/disabled states;
- form validation;
- route/guard behavior;
- accessibility with user impact;
- responsive interaction/layout;
- backend contract drift.

### 4.10 Maintainability / performance

Report only concrete risk/cost:

- duplicated domain logic likely to diverge;
- complexity obscuring correctness;
- misleading names/comments likely to cause misuse;
- expensive hot-path query/render/network behavior;
- needless calls;
- missed shared abstractions;
- architecture drift increasing defect risk.

Do not flood review with personal style preferences.

---

## 5. Finding Severity

- **P0 Critical:** money/security/data corruption/double booking/production outage or comparable severe invariant violation.
- **P1 High:** likely user-visible correctness failure, major race, auth issue, serious migration/integration defect.
- **P2 Medium:** real bounded defect/reliability/testability/maintainability problem.
- **P3 Low:** worthwhile non-blocking improvement with concrete benefit.

Do not inflate severity.

---

## 6. Finding Quality Standard

Every actionable finding must include:

```text
ID: REV-001
Severity: P0/P1/P2/P3
Category: Correctness/Concurrency/Security/DB/API/Messaging/Frontend/Tests/Performance/Maintainability
Location: exact file + line/symbol
Problem: ...
Evidence: ...
Failure scenario: ...
Required fix: desired behavior
Test expectation: ... | N/A + reason
Status: OPEN
```

Do not report speculative concerns without a plausible failure scenario or contract violation. Investigate first.

---

## 7. Temporary Findings Ledger

If **any actionable finding** exists create/update:

`.ai/tmp/review-<task-id-or-branch>.md`

Header must clearly say it is temporary, ignored, and must not be committed.

Suggested finding template:

```md
## REV-001 — <short title>

- Severity: P1
- Category: Correctness
- Status: OPEN
- Location: `path/File.java:120-145`
- Problem: ...
- Evidence: ...
- Failure scenario: ...
- Required fix: ...
- Test expectation: ...
- Resolution evidence: _filled by fixer_
```

Lifecycle:

```text
review
-> findings ledger
-> fixer validates/repairs
-> regression/targeted verification
-> re-review substantive/high-risk fixes
-> all accepted findings resolved
-> delete ledger
-> final QA
```

Deleting the ledger before repair completion is a process violation.

---

## 8. Re-Review Rules

Re-review is mandatory for:

- all P0/P1;
- concurrency/payment/security/idempotency/distributed-consistency/dangerous migration fixes;
- fixes that materially change the implementation approach;
- multiple interacting P2 fixes with meaningful behavioral impact.

During re-review verify:

1. original finding is truly resolved;
2. regression test/evidence is meaningful;
3. fix did not create a nearby new failure mode.

Small localized P2/P3 corrections with strong deterministic evidence may use targeted re-review.

---

## 9. Model Routing Semantics

Do not keep model tables in this workflow. Resolve every review through `.ai/MODEL_ROUTER.md`.

Operational intent:

- small review -> Codex/Terra Medium, Fast OFF;
- substantive review -> Codex/Terra High, Fast OFF;
- critical money/security/concurrency/data-integrity review -> Codex/Sol High, Fast OFF;
- Gemini High is the normal alternative when Codex quota/provider availability or model-family diversity justifies it.

If implementation was performed by Codex, prefer an independent Antigravity/Gemini normal-risk review rather than same-model self-approval.

Never silently enable Codex Fast.

---

## 10. Review Verdict

Return one:

- `APPROVE`
- `APPROVE WITH NON-BLOCKING P3 NOTES`
- `CHANGES REQUIRED`
- `CRITICAL CHANGES REQUIRED`

P0/P1/P2 correctness/security/contract/invariant/migration/meaningful test-gap findings are blocking unless the review provides concrete evidence why they are outside the task or not actually required.

---

## 11. Anti-Nitpicking Rules

Do not create findings solely for:

- subjective naming when current naming is clear;
- formatting handled by tooling;
- replacing one valid idiom with another;
- comments/docstrings with no correctness value;
- speculative extensibility;
- premature abstraction;
- micro-optimization with no realistic hot-path impact.

The goal is fewer escaped defects, not a longer report.

---

## 12. Orchestrated Output / Next Stage

Return structured state:

```text
STAGE: REVIEW | RE-REVIEW
TASK: ...
PROVIDER/MODEL/EFFORT: ...
FAST MODE: ...
AREAS INSPECTED: ...
FINDINGS: ordered by severity
LEDGER: path | none
TEST/VERIFICATION LIMITATIONS: ...
VERDICT: ...
NEXT STAGE: FIX | FINAL QA
```

### If changes are required

Supervisor routes to `.ai/workflows/03-bug-fixing.md`, resolving the fixer/diagnoser through `.ai/MODEL_ROUTER.md`.

Clear/localized findings normally use the Muse route (`Free -> Go`); difficult root-cause work may first require Terra diagnosis; critical findings use the critical risk override.

### If approved

Supervisor routes to `.ai/workflows/04-testing-and-qa.md`.

### Manual fallback

If delegation is unavailable, provide a complete Next Stage Handoff with exact task/branch/ledger context and router-derived model recommendation.
