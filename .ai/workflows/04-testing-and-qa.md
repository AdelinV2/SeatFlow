# Workflow 04: Testing, QA & Final Quality Gate

**Role:** QA / Test Engineer

---

## 1. Goal

Verify that a SeatFlow change is not only green on its happy path, but also robust against realistic failure modes, regressions, contract drift, concurrency mistakes, unsafe migrations, frontend state bugs, and incomplete review cleanup.

QA is the **final completion gate** after implementation, debugging, and code review. Passing a single targeted test is not sufficient evidence for a non-trivial change.

---

## 2. Testing Pyramid

```text
          ┌────────────────────────────────┐
          │ E2E / Cross-Service Scenarios  │
          ├────────────────────────────────┤
          │ Integration / Testcontainers   │
          ├────────────────────────────────┤
          │ Concurrency / Failure Tests    │
          ├────────────────────────────────┤
          │ Slice / Contract Tests         │
          ├────────────────────────────────┤
          │ Unit Tests                     │
          └────────────────────────────────┘
```

Use the lowest-cost layer that can **actually prove the behavior**. Do not use a mocked unit test as evidence for a property that depends on PostgreSQL locking, transaction boundaries, Kafka delivery, Stripe semantics, browser behavior, or WebSocket reconnects.

---

## 3. Backend Test Standards

### 3.1 Unit Tests

- JUnit 5 + Mockito where isolation is appropriate.
- Test pure domain logic, validation, mapping, branching, and deterministic service behavior.
- Mock repositories/external clients only when the property under test does not depend on their real semantics.
- Assertions must verify meaningful outputs/state/interactions, not merely that a method returns without throwing.

### 3.2 Slice Tests

Use focused Spring slices when they provide real value:

- repository behavior with `@DataJpaTest`;
- controller/security/validation behavior with web slice tests;
- mapper/serialization/contract behavior where useful.

Do not over-mock the exact behavior you intended to verify.

### 3.3 Integration Tests

For persistence, messaging, and service behavior that depends on real infrastructure:

- use `@SpringBootTest` / Testcontainers as established by the module;
- use real PostgreSQL for constraints, locking, transactions, and Flyway behavior;
- use real Kafka/Testcontainers where delivery, serialization, idempotency, or ordering is the behavior under test;
- use `@DynamicPropertySource` or the repository's established test configuration rather than hardcoded ports.

### 3.4 Concurrency Tests

For Reservation Service and any change affecting seat ownership/holds:

- simulate **50-100 concurrent contenders** where practical;
- synchronize starts using deterministic primitives such as `CountDownLatch` / barriers;
- assert the domain outcome, not merely thread completion;
- for the same seat/hold invariant, assert exactly one allowed success and the expected rejection/conflict behavior for the rest;
- never use arbitrary `sleep()` as the primary synchronization mechanism;
- never disable concurrency tests because they expose a race.

---

## 4. Frontend Test Standards

For Angular code:

- use Angular testing utilities and the repository's current Angular 22 patterns;
- verify Signals/computed state updates;
- verify loading, error, empty, success, and disabled states when relevant;
- test user interactions and form validation;
- test API request shape and error handling;
- test route guards/ownership assumptions only for UX behavior — backend authorization remains authoritative;
- test stale request / reconnect / reconciliation behavior when the feature is vulnerable to races;
- verify cleanup when components/services create subscriptions, timers, listeners, or WebSocket resources.

Visual implementation should also be checked at relevant responsive breakpoints when the task changes layout or interaction behavior.

---

## 5. Risk-Based Test Matrix

For each task, map critical failure modes to evidence:

| Risk | Minimum useful evidence |
|---|---|
| Validation / boundaries | unit + controller/contract test |
| DB constraints / mapping | real PostgreSQL integration test |
| Transaction boundaries | integration test + code review |
| Double booking / locking | deterministic concurrency test + DB invariant |
| Idempotency | repeated/parallel request tests + persistence assertion |
| Kafka duplicate delivery | consumer idempotency integration test |
| Payment/webhook state | state-transition + duplicate/out-of-order scenario tests |
| Authorization / ownership | backend security/controller integration tests |
| Migration safety | Flyway migration against representative schema/data |
| Frontend state races | controlled async tests + browser/manual check where needed |
| WebSocket reconnect | reconnect + authoritative reconciliation scenario |

Tests are evidence; review remains required for hidden failure modes that are hard to exhaustively simulate.

---

## 6. Test Quality Review

Before accepting a green suite, inspect the tests themselves for weak or misleading oracles:

- assertions that only check non-null / HTTP 2xx while ignoring state;
- mocks configured to return exactly the expected answer without testing logic;
- missing negative cases;
- tests that never execute the changed branch;
- swallowed exceptions;
- excessive timing assumptions;
- duplicated tests that add no new coverage;
- integration tests accidentally using H2/in-memory semantics instead of PostgreSQL for DB-specific behavior;
- concurrency tests whose threads never truly overlap;
- frontend tests that assert implementation details instead of user-visible/domain behavior.

Do not inflate test count or coverage percentage at the expense of useful failure detection.

---

## 7. Verification Sequence

Run checks in increasing scope so failures are fast to diagnose:

```text
1. Exact verification command from the task file.
2. New/modified regression tests.
3. Relevant module/service test suite.
4. Build / compile / typecheck / lint as applicable.
5. Relevant integration / Testcontainers / concurrency tests.
6. Relevant frontend browser/manual interaction checks when automated tests cannot prove visual behavior.
7. Broader repository checks required by the changed contracts or CI surface.
```

Use the repository's actual scripts and build configuration. Do not invent commands when the task/package/POM already defines them.

---

## 8. Final Quality Gate

Before a task can move to `completed/`, verify all of the following.

### Correctness & Scope

- Task objective and every acceptance criterion are satisfied.
- Critical invariants remain intact.
- No unrelated behavior/refactor slipped into the diff without justification.
- API, event, DB, and frontend contracts are consistent.
- New environment variables are reflected in `.env.example` where appropriate.

### Tests & Build

- Targeted tests pass.
- Required regression/integration/concurrency tests pass.
- Relevant build/typecheck/lint checks pass.
- New tests have meaningful assertions and would fail if the defect/behavior regressed.

### Review

- `.ai/workflows/05-code-review.md` has been completed for substantive changes.
- All accepted findings are resolved.
- High-risk/substantive fixes were re-reviewed when required.
- No active `.ai/tmp/review-*.md` file remains for the task.

### Diff Hygiene

Inspect the final diff for:

- accidental TODO/FIXME markers;
- debug logging / `console.log` / temporary instrumentation;
- commented-out production code;
- dead imports/files;
- broad formatting churn;
- generated/binary artifacts that should not be committed;
- secrets, credentials, `.env`, tokens, keys, or private production data;
- stale comments/documentation that now contradict behavior.

### Migration / Operational Safety

When applicable:

- migration order is valid;
- entity mappings match DDL;
- constraints/indexes support the invariant;
- backward compatibility and rollout assumptions are understood;
- no destructive migration is treated as automatically reversible;
- metrics/logs/traces needed to diagnose the new behavior are present and do not expose secrets/PII.

---

## 9. Review-File Cleanup Rule

The temporary reviewer ledger under `.ai/tmp/` is deliberately ignored by Git and exists only during a review/fix cycle.

QA must fail the completion gate if an active task still has a review ledger because that means one of three things is true:

1. findings are unresolved;
2. re-review/verification is incomplete; or
3. cleanup was forgotten.

The correct sequence is:

`review -> findings ledger -> fixes -> verification -> re-review if needed -> all findings resolved -> delete ledger -> final QA pass`

Never delete the ledger early merely to make the quality gate appear clean.

---

## 10. Completion Output

QA should report:

- commands/checks executed;
- pass/fail status;
- any checks not executed and the concrete reason;
- critical invariants verified;
- remaining risks/limitations, if any;
- final decision: `PASS`, `PASS WITH NON-BLOCKING NOTES`, or `FAIL`.

Use `PASS WITH NON-BLOCKING NOTES` only for genuine low-risk improvements that do not contradict the task, architecture, security, data integrity, or correctness. Bugs and contract violations are blocking.

---

## 11. Next Stage Handoff

Depending on the final QA decision, proceed to the corresponding next stage:

### 11.1 Branch A: If QA Decision is `PASS` or `PASS WITH NON-BLOCKING NOTES`

The task is verified and ready to be finalized. Proceed to **Task Finalization & Git Integration** (`AGENTS.md` Section 6 and Section 9).

#### AI Model & Reasoning Effort Selection

Consult `.ai/MODEL_ROUTER.md` (low-risk deterministic git/doc operations):

| Operation | Recommended Route | Alternative Route |
|---|---|---|
| **Task File Archival & Git PR Operations** | **GPT-5.6 Luna Low / Medium** | **Gemini 3.8 Flash Medium** |

#### Prompt Template for Task Finalization

Copy and paste the following prompt:

```markdown
You are finalizing the completed task for SeatFlow.
Task: [TASK-P<XX>-<YYY>: <Task Title>]
Active task file: `.ai/tasks/phase-<XX>-<service-name>/<YYY>-<task-desc>.md`
Target completed path: `.ai/tasks/completed/phase-<XX>-<service-name>/<YYY>-<task-desc>.md`
Branch: `feat/p<XX>-<YYY>-<task-desc>`

Instructions:
1. Verify that the QA gate has returned PASS or PASS WITH NON-BLOCKING NOTES.
2. Ensure no active `.ai/tmp/review-*.md` file exists.
3. Move the task file to the completed folder:
   `git mv .ai/tasks/phase-<XX>-<service-name>/<YYY>-<task-desc>.md .ai/tasks/completed/phase-<XX>-<service-name>/<YYY>-<task-desc>.md`
4. Stage and commit all changes following Conventional Commits format:
   `git add .`
   `git commit -m "feat(<scope>): <concise description satisfying TASK-P<XX>-<YYY>"`
5. Provide instructions/command to push the branch and open a PR targeting `develop`:
   `git push -u origin feat/p<XX>-<YYY>-<task-desc>`
```

---

### 11.2 Branch B: If QA Decision is `FAIL`

Hand off back to **Workflow 03: Bug Fixing & Debugging Protocol** (`.ai/workflows/03-bug-fixing.md`).

#### AI Model & Reasoning Effort Selection

| QA Failure Type | Recommended Route | Alternative Route | Escalation |
|---|---|---|---|
| **Targeted Test / Assertion / Hygiene Failure** | **Muse Spark 1.3 xHigh** | **Gemini 3.8 Flash High** | **GPT-5.6 Terra High** |
| **Systemic Contract / Architectural / Concurrency Issue** | **GPT-5.6 Terra High** | **Gemini 3.8 Flash High** | **GPT-5.6 Sol High** (if critical invariant) |

#### Prompt Template for QA Defect Repair

Copy and paste the following prompt:

```markdown
You are the Fixer / Debugger for SeatFlow resolving QA failures.
Task: [TASK-P<XX>-<YYY>: <Task Title>]
Branch: `feat/p<XX>-<YYY>-<task-desc>`

Instructions:
1. Follow `.ai/workflows/03-bug-fixing.md`.
2. Inspect and diagnose the specific QA failure report:
   - Failing tests/commands: `<list failing commands and traces>`
   - Diff hygiene / contract violations: `<list findings>`
3. Reproduce the failure, apply the minimal root-cause fix, and add/adjust regression assertions.
4. Verify using: `<verification-command>`
5. Re-run `.ai/workflows/04-testing-and-qa.md` once clean.
```
