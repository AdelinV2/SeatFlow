# Workflow 05: Code Review Protocol

**Role:** Independent Reviewer / Quality Gate

---

## 1. Goal

Perform a **proactive, evidence-based review** of a completed implementation to discover bugs, correctness errors, security issues, broken invariants, integration mismatches, test gaps, and meaningful maintainability/performance improvements before the task is finalized.

The reviewer is not a formatter or style critic. The priority is to find realistic failure modes that could survive compilation and tests.

Review the **diff first**, but inspect surrounding code, call sites, migrations, tests, contracts, ADRs, and architecture whenever they are needed to judge the changed behavior correctly.

---

## 2. Reviewer Independence

The reviewer should approach the implementation as if it was written by another engineer.

Do not assume correctness because:

- the implementer followed the task;
- tests are green;
- the implementation looks idiomatic;
- the model/engineer that wrote the code is strong;
- a similar pattern exists elsewhere in the repository.

Passing tests are evidence, not proof, especially for concurrency, payments, security, idempotency, migrations, distributed state, retries, and browser/WebSocket races.

Model selection and reasoning effort are governed by `.ai/MODEL_ROUTER.md`. Critical reservation/payment/security/data-integrity reviews must use the project's stronger review path.

---

## 3. Review Inputs

Before reviewing, gather:

1. the task file and acceptance criteria;
2. relevant ADRs and `.ai/architecture/` docs;
3. `backend/AGENTS.md` or `frontend/AGENTS.md`;
4. the complete branch/PR diff;
5. new and modified tests;
6. relevant neighboring code and call sites;
7. relevant DB migrations, API/event contracts, and frontend models;
8. verification results when available.

Do not review only the final source file if correctness depends on interactions elsewhere.

---

## 4. Review Order

Review in this order so the highest-value defects are found first.

### 4.1 Task & Contract Compliance

Check whether the implementation actually satisfies the task and authoritative contracts:

- every acceptance criterion;
- exact DTO/API/event/schema contract;
- required state transitions;
- ADR decisions;
- repository coding/architecture rules;
- no unapproved scope expansion or hidden behavior change.

A polished implementation of the wrong contract is still wrong.

### 4.2 Correctness & Edge Cases

Actively search for:

- missing branches and incorrect conditions;
- off-by-one/boundary errors;
- null/empty/missing state handling;
- invalid state transitions;
- incorrect time comparisons/time-zone assumptions;
- money/rounding/precision mistakes;
- incorrect exception/status mapping;
- stale state and lost updates;
- partial-success paths;
- behavior after retry/replay/reconnect;
- cleanup/resource leaks;
- hidden assumptions that are not enforced.

For each suspected issue, construct a concrete failure scenario before reporting it.

### 4.3 SeatFlow Critical Invariants

When relevant, explicitly verify:

- maximum 10 seats per reservation;
- strict 15-minute hold semantics;
- zero double-booking under concurrency;
- PostgreSQL as source of truth;
- transactional outbox semantics;
- idempotency for reservation/payment flows;
- valid payment/refund/webhook state transitions;
- server-side authentication/authorization;
- distributed consistency and ordering assumptions;
- migration/data-integrity safety.

Do not accept application-level checks as sufficient when the invariant requires atomic DB enforcement.

### 4.4 Concurrency & Distributed Behavior

Look for:

- check-then-act / TOCTOU races;
- missing/incorrect transaction boundaries;
- inconsistent lock ordering;
- stale reads;
- optimistic locking without correct retry behavior;
- duplicate Kafka/event processing;
- non-idempotent consumers/side effects;
- unsafe retry loops;
- out-of-order event assumptions;
- external call + DB partial-failure windows;
- cache state being treated as authoritative;
- reconnect races and server/client state divergence.

### 4.5 Security & Privacy

Check:

- authentication and role checks;
- ownership/resource authorization and IDOR risk;
- input validation at trust boundaries;
- mass-assignment / over-posting style mistakes;
- secret/token/credential exposure;
- sensitive PII/payment data in logs/errors;
- unsafe CORS/security assumptions;
- webhook verification;
- privilege escalation through alternate endpoints/state transitions;
- frontend-only enforcement of backend rules.

### 4.6 Database & Migrations

Inspect:

- entity ↔ DDL alignment;
- uniqueness/FK/check/index constraints;
- nullability/defaults;
- cascade/delete behavior;
- query correctness and N+1 risks with material impact;
- migration ordering and compatibility;
- data backfill assumptions;
- locking/query behavior under real PostgreSQL semantics;
- destructive or irreversible changes.

### 4.7 API, Messaging & Integration Contracts

Check:

- HTTP method/path/status/body correctness;
- validation and error envelope compatibility;
- backend DTO ↔ frontend model alignment;
- event names/topics/payload schema;
- serialization/deserialization behavior;
- backward compatibility where required;
- retry/idempotency semantics across service boundaries;
- Eureka/LoadBalancer/shared-client patterns required by repository rules.

### 4.8 Tests

Review the tests as critically as production code.

Look for:

- missing negative/boundary cases;
- weak assertions;
- tests that do not execute the changed branch;
- mocks that encode the expected result rather than test behavior;
- missing regression tests for discovered bugs;
- integration behavior tested only as a unit;
- concurrency tests without true overlap;
- flaky timing assumptions;
- missing authorization/idempotency/retry cases;
- frontend async/reconnect/loading/error behavior left untested.

### 4.9 Frontend Quality

When applicable, check:

- Angular Signal state correctness;
- stale/computed state;
- duplicate or racing HTTP requests;
- optimistic update rollback/reconciliation;
- WebSocket reconnect behavior;
- resource/timer/subscription cleanup;
- loading/error/empty/disabled states;
- form and input validation;
- route/guard behavior;
- accessibility regressions with real user impact;
- responsive interaction/layout problems;
- backend contract mismatch.

### 4.10 Maintainability & Performance

Report improvements only when they have a concrete cost or risk:

- duplicated domain logic likely to diverge;
- needless complexity obscuring correctness;
- misleading names/comments that can cause misuse;
- expensive query/loop/render behavior on realistic hot paths;
- unnecessary network/DB calls;
- missing reuse of established shared abstractions;
- architecture drift that increases future defect risk.

Do **not** flood the review with personal style preferences or micro-optimizations.

---

## 5. Finding Severity

Use these levels:

- **P0 — Critical:** money/security/data corruption/double booking/production outage or equivalent severe invariant violation.
- **P1 — High:** likely user-visible correctness failure, broken contract, major race, authorization issue, serious migration/integration defect.
- **P2 — Medium:** real defect or meaningful reliability/testability/maintainability problem with bounded impact.
- **P3 — Low:** worthwhile non-blocking improvement with a concrete benefit.

Do not inflate severity. A finding should be as severe as its plausible impact, not as dramatic as the category sounds.

---

## 6. Finding Quality Standard

Every reported finding must contain:

- **ID:** `REV-001`, `REV-002`, ...
- **Severity:** P0/P1/P2/P3
- **Category:** Correctness / Concurrency / Security / DB / API / Messaging / Frontend / Tests / Performance / Maintainability
- **Location:** file + line/range or exact symbol
- **Problem:** what is wrong
- **Evidence:** code/contract/repository rule that supports the claim
- **Failure scenario:** a concrete realistic sequence that exposes the issue
- **Required fix:** desired behavior, not an unnecessary full implementation prescription
- **Test expectation:** regression/verification needed, or `N/A` with reason
- **Status:** `OPEN`

Do not report a speculative issue when you cannot explain a plausible failure scenario or contract violation. If uncertain, investigate further before creating a finding.

---

## 7. Temporary Review Findings File

### 7.1 When to Create It

If the review finds **any actionable bug, error, contract violation, test gap, or accepted improvement**, create one temporary local file:

`.ai/tmp/review-<task-id-or-branch>.md`

If there are no actionable findings, do not create an empty file.

The `.ai/tmp/` directory is ignored by Git and the file must never be committed.

### 7.2 Mandatory Header

The file must begin with wording equivalent to:

```md
# TEMPORARY REVIEW FINDINGS — DELETE AFTER RESOLUTION

> This file is a temporary local repair ledger. Do not commit it.
> Delete this file only after every accepted finding is resolved,
> required tests/verification pass, and any required re-review is complete.
```

### 7.3 Finding Template

```md
## REV-001 — <short title>

- Severity: P1
- Category: Correctness
- Status: OPEN
- Location: `path/to/File.java:120-145`
- Problem: ...
- Evidence: ...
- Failure scenario: ...
- Required fix: ...
- Test expectation: ...
- Resolution evidence: _filled by fixer_
```

The fixer follows `.ai/workflows/03-bug-fixing.md` and updates statuses during repair.

### 7.4 Lifecycle

```text
Review
→ create temporary findings ledger if needed
→ fixer validates and repairs findings
→ regression/targeted verification
→ re-review substantive/high-risk changes
→ all accepted findings RESOLVED
→ required verification passes
→ delete temporary findings file
→ final QA gate
```

Deleting the file before findings are resolved is a process violation, not a valid fix.

---

## 8. Re-Review Rules

Re-review is mandatory for:

- all P0/P1 findings;
- concurrency, payment, security, idempotency, distributed consistency, and dangerous migration fixes;
- fixes that materially change the original implementation approach;
- multiple interacting P2 fixes where the combined patch changes behavior substantially.

For a small localized P2/P3 correction with strong deterministic verification, targeted re-review may be enough.

During re-review, verify both:

1. the original finding is actually resolved; and
2. the fix did not create a new failure mode nearby.

---

## 9. Review Output

The reviewer should finish with:

- findings ordered by severity;
- a short statement of areas inspected;
- important checks performed even if no issue was found;
- tests/verification gaps that limit confidence;
- final decision:
  - `APPROVE`
  - `APPROVE WITH NON-BLOCKING P3 NOTES`
  - `CHANGES REQUIRED`
  - `CRITICAL CHANGES REQUIRED`

P0/P1/P2 correctness, security, contract, invariant, migration, or meaningful test-gap findings are blocking unless the reviewer explicitly demonstrates why they are not required for the task.

---

## 10. Anti-Nitpicking Rules

Do not create findings solely for:

- subjective naming preferences when the current name is clear;
- formatting already handled by project tooling;
- replacing one equally valid idiom with another;
- comments/docstrings that add no correctness value;
- speculative future extensibility;
- premature abstractions;
- micro-performance changes with no realistic hot-path impact.

The goal is **better software and fewer escaped defects**, not a longer review.

---

## 11. Next Stage Handoff

Depending on the final review decision, proceed to the corresponding next stage:

### 11.1 Branch A: If Review Decision is `CHANGES REQUIRED` or `CRITICAL CHANGES REQUIRED`

Hand off to **Workflow 03: Bug Fixing & Debugging Protocol** (`.ai/workflows/03-bug-fixing.md`) using the temporary findings ledger `.ai/tmp/review-<task-id>.md`.

#### AI Model & Reasoning Effort Selection

Consult `.ai/MODEL_ROUTER.md` based on finding complexity:

| Finding Nature | Recommended Route | Alternative Route | Escalation / High Risk |
|---|---|---|---|
| **Clear / Localized / Reproducible Defect** | **Muse Spark 1.3 High / xHigh** | **Gemini 3.8 Flash High** | **GPT-5.6 Terra High** (after 1 failed repair) |
| **Difficult Root Cause / Architecture Reasoning** | **GPT-5.6 Terra High** | **Gemini 3.8 Flash High** | **GPT-5.6 Terra xHigh** |
| **Critical Invariant / Concurrency / Money / Security** | **GPT-5.6 Sol High** | **GPT-5.6 Terra xHigh** | **GPT-5.6 Sol xHigh** (if unresolved) |

#### Prompt Template for Bug Fixing

Copy and paste the following prompt to invoke the fixer:

```markdown
You are the Fixer / Debugger for SeatFlow.
Address review findings for task: [TASK-P<XX>-<YYY>: <Task Title>]
Branch: `feat/p<XX>-<YYY>-<task-desc>`
Review findings ledger: `.ai/tmp/review-p<XX>-<YYY>.md`

Instructions:
1. Follow `.ai/workflows/03-bug-fixing.md`.
2. Inspect each finding in `.ai/tmp/review-p<XX>-<YYY>.md`:
   - Set status to `IN_PROGRESS`.
   - Reproduce with the narrowest failing test where practical.
   - Apply the minimal surgical root-cause fix preserving architecture.
   - Add/strengthen automated regression tests.
   - Run verification and set status to `RESOLVED` with evidence (or `REJECTED_WITH_REASON`).
3. Re-run task verification: `<verification-command>`
4. When all findings are resolved and verified:
   - If findings include P0/P1 or critical invariants, hand off for Re-Review (`.ai/workflows/05-code-review.md`).
   - Otherwise, delete `.ai/tmp/review-p<XX>-<YYY>.md` and hand off to QA (`.ai/workflows/04-testing-and-qa.md`).
```

---

### 11.2 Branch B: If Review Decision is `APPROVE` or `APPROVE WITH NON-BLOCKING P3 NOTES`

Ensure no active `.ai/tmp/review-*.md` file remains, and hand off to **Workflow 04: Testing, QA & Final Quality Gate** (`.ai/workflows/04-testing-and-qa.md`).

#### AI Model & Reasoning Effort Selection

| Gate Validation | Recommended Route | Alternative Route | Escalation |
|---|---|---|---|
| **Final QA & Gate Verification** | **Gemini 3.8 Flash High** | **GPT-5.6 Terra High** | **Muse Spark 1.3 xHigh** |

#### Prompt Template for Final QA

Copy and paste the following prompt to invoke QA:

```markdown
You are the QA / Test Engineer for SeatFlow.
Execute the final quality gate for task: [TASK-P<XX>-<YYY>: <Task Title>]
Branch: `feat/p<XX>-<YYY>-<task-desc>`
Task specification: `.ai/tasks/phase-<XX>-<service-name>/<YYY>-<task-desc>.md`

Instructions:
1. Follow `.ai/workflows/04-testing-and-qa.md`.
2. Verify that independent code review is complete and no active `.ai/tmp/review-*.md` ledger remains.
3. Run the comprehensive verification sequence:
   - Task verification command: `<verification-command>`
   - Service test suite: `<mvn test / npm test>`
   - Build / typecheck / lint
   - Concurrency / Integration / Testcontainers checks where applicable.
4. Perform diff hygiene review: check for stray TODOs, debug logs, commented code, or credentials.
5. Validate all acceptance criteria and invariant rules.
6. Deliver final verdict: `PASS`, `PASS WITH NON-BLOCKING NOTES`, or `FAIL`.
```
