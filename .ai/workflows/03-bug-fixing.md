# Workflow 03: Bug Fixing & Debugging Protocol

**Role:** Fixer / Debugger

---

## 1. Goal

Resolve compiler errors, test failures, runtime bugs, business-logic defects, review findings, concurrency races, integration failures, and regressions by identifying and correcting the **root cause** with the smallest safe change.

A bug is not considered fixed until the failure is reproduced where practical, the root cause is understood, regression coverage exists when meaningful, and relevant verification passes.

---

## 2. Core Rules

1. **Reproduce before fixing whenever practical.** For a reported behavioral defect without an adequate test, write the smallest failing regression test first.
2. **Fix the cause, not the symptom.** Do not add broad catches, null guards, retries, sleeps, or fallback behavior unless they are correct by contract.
3. **Never delete or disable a failing test to make CI green.** Do not weaken assertions or replace meaningful integration behavior with mocks merely to hide the failure.
4. **Minimal surgical edits.** Avoid unrelated refactors while debugging.
5. **Preserve architecture and invariants.** A bug fix must not create a new contract, migration policy, consistency model, or security rule without planning/ADR review.
6. **Use evidence.** Error traces, failing tests, SQL state, request/response data, logs, metrics, and code paths are stronger than guesses.
7. **One hypothesis at a time.** Do not make many speculative edits and then infer which one fixed the problem.
8. **Tests passing is necessary but not always sufficient.** Explicitly reason about concurrency, payments, security, idempotency, retries, migrations, and distributed consistency.

---

## 3. Issue Classification

Classify the failure before editing:

- Compilation / type / dependency error
- Runtime exception
- Validation / API contract error
- Business invariant violation
- Persistence / migration / transaction error
- Concurrency / locking / race condition
- Kafka / outbox / retry / ordering issue
- Payment / Stripe / idempotency issue
- Authentication / authorization / security issue
- Frontend state / rendering / routing / request race
- WebSocket / reconnect / stale-state issue
- CI / Docker / environment / observability issue
- Performance/resource issue with measurable impact

Also classify **risk** as Low / Medium / High / Critical. Critical bugs involving money, security, double booking, corrupted state, destructive migrations, or distributed correctness require the stronger review path defined in `.ai/MODEL_ROUTER.md`.

---

## 4. Root-Cause Sequence

```text
1. Read the exact failure report, review finding, stack trace, or failing test.
2. Read the relevant task, ADRs, architecture docs, and AGENTS rules.
3. Identify the expected contract/invariant.
4. Trace the failing path from input/event/state to the observed failure.
5. Reproduce with the narrowest deterministic test or command available.
6. Confirm the regression test fails for the expected reason.
7. Form a concrete root-cause hypothesis.
8. Inspect adjacent call sites/state transitions/DB constraints as needed to validate the hypothesis.
9. Apply the smallest correct fix.
10. Run the regression test.
11. Run relevant neighboring/module/integration tests.
12. Review the diff for new failure modes and unintended scope.
```

If reproduction is impossible because the issue depends on production-only timing or infrastructure, document the evidence and create the strongest deterministic approximation available. Do not fabricate certainty.

---

## 5. Review Findings Workflow

When the issue comes from `.ai/workflows/05-code-review.md`, the reviewer may create a temporary local file:

`.ai/tmp/review-<task-or-branch>.md`

That file is the working ledger for the repair pass.

For every accepted finding:

1. Change status from `OPEN` to `IN_PROGRESS` before working on it.
2. Reproduce or otherwise validate the finding.
3. Apply the targeted fix.
4. Add or strengthen a regression test when the defect can recur and a meaningful automated oracle exists.
5. Record the verification command/evidence.
6. Mark the finding `RESOLVED` only after verification passes.
7. If a finding is invalid, mark `REJECTED_WITH_REASON` and explain concretely why; do not silently ignore it.

After all findings are `RESOLVED` or legitimately `REJECTED_WITH_REASON`:

- rerun the required task verification and affected regression checks;
- request re-review for substantive/high-risk fixes;
- ensure no new accepted findings remain;
- **delete the temporary `.ai/tmp/review-*.md` file only after the repair/re-review cycle is complete.**

The temporary file itself must contain a prominent note that it must be deleted after all accepted findings are resolved. It must never be committed.

---

## 6. Debugging Heuristics by Failure Type

### Concurrency / Double Booking

Check transaction boundaries, DB uniqueness, locking mode/order, isolation assumptions, stale reads, retry behavior, and whether the invariant is enforced atomically in PostgreSQL. Reproduce with concurrent actors, not sequential mocks.

### Idempotency / Payments

Check key scope, uniqueness, replay behavior, state transitions, webhook ordering, duplicate delivery, partial failure between external provider and DB state, and refund/payment terminal states. Never "fix" duplicate processing by swallowing exceptions blindly.

### Kafka / Outbox

Check transaction ownership, outbox persistence, event IDs, consumer idempotency, duplicate delivery, ordering assumptions, retry/DLQ behavior, and whether side effects can execute more than once.

### Database / Flyway

Check current schema, migration order, nullability/defaults, constraints/indexes, backward compatibility, data backfill assumptions, and whether entity mappings match DDL exactly.

### Frontend State

Check request races, stale signals/computed state, optimistic updates, server reconciliation, loading/error/empty states, route lifecycle, WebSocket reconnect behavior, duplicated subscriptions/requests, and cleanup of resources.

### Security

Check authentication, role/ownership authorization, IDOR paths, input validation, token/secret exposure, sensitive logging, CSRF/CORS assumptions where relevant, and trust boundaries between frontend and backend.

---

## 7. Anti-Patterns

Do not:

- add arbitrary retry loops or `sleep()` to hide races;
- catch `Exception` and continue without a defined recovery contract;
- return success when the operation actually failed;
- remove DB constraints because application code conflicts with them;
- make tests less strict to accommodate wrong behavior;
- duplicate shared abstractions instead of using `backend/common/`;
- rewrite a whole class because one branch is wrong;
- mix speculative cleanup into a targeted bug fix;
- assume a flaky test is "just flaky" without investigating the failure mode.

---

## 8. Completion Criteria

A bug/review finding is complete only when:

- the expected contract is explicit;
- root cause is identified with evidence;
- the fix is minimal and architecture-compliant;
- regression coverage exists when meaningful;
- targeted verification passes;
- affected broader checks pass;
- the final diff does not introduce unrelated changes;
- substantive/high-risk review findings have been re-reviewed;
- any temporary `.ai/tmp/review-*.md` ledger has been deleted after all findings are resolved.
