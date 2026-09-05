# Workflow 03: Bug Fixing & Debugging Protocol

**Role:** Fixer / Debugger

---

## 1. Goal

Resolve compiler errors, failing tests, runtime defects, review findings, regressions, integration failures, races, and broken business behavior by identifying and correcting the **root cause with the smallest safe change**.

A bug is not fixed merely because the symptom disappears. Reproduce/validate where practical, understand the failing contract, add meaningful regression evidence, and rerun the relevant quality gates.

Provider/model/effort selection is centralized in `.ai/MODEL_ROUTER.md`.

---

## 2. Core Rules

1. Reproduce before fixing whenever practical.
2. Fix root cause, not symptom.
3. Never delete/disable/weaken a meaningful failing test to obtain green output.
4. Keep edits surgical; avoid unrelated refactors.
5. Preserve architecture, contracts, and invariants.
6. Use evidence: traces, failing tests, SQL state, logs, requests/responses, events, metrics, and code paths.
7. Test one concrete hypothesis at a time.
8. Passing tests are necessary but may be insufficient for concurrency, payments, security, idempotency, migrations, retries, and distributed state.
9. Preserve unrelated user working-tree changes.

---

## 3. Classify the Failure

Classify before editing:

- compile/type/dependency;
- runtime exception;
- validation/API contract;
- business invariant;
- persistence/migration/transaction;
- concurrency/locking/race;
- Kafka/outbox/retry/order;
- payment/Stripe/idempotency;
- authentication/authorization/security;
- frontend state/render/routing/request race;
- WebSocket/reconnect/stale state;
- CI/Docker/environment/observability;
- measurable performance/resource problem.

Also classify risk `Low | Medium | High | Critical` and verification strength.

Critical money/security/double-booking/data-integrity/distributed-consistency failures require the stronger routing/review path from `.ai/MODEL_ROUTER.md`.

---

## 4. Root-Cause Sequence

```text
1. Read the exact failure/review finding and expected contract.
2. Read relevant task, ADRs, architecture, AGENTS rules.
3. Trace the failing path from input/event/state to observed result.
4. Reproduce with the narrowest deterministic test/command when practical.
5. Confirm the reproduction fails for the expected reason.
6. Form one concrete root-cause hypothesis.
7. Inspect adjacent call sites/state/DB constraints/contracts to validate it.
8. Apply the smallest correct fix.
9. Run the regression test.
10. Run affected neighboring/module/integration checks.
11. Inspect the complete diff for new failure modes/unintended scope.
```

If a production-only timing/infrastructure issue cannot be reproduced exactly, document the evidence and create the strongest deterministic approximation available. Never fabricate certainty.

---

## 5. Review Findings Ledger

When the issue comes from `.ai/workflows/05-code-review.md`, use:

`.ai/tmp/review-<task-or-branch>.md`

For every accepted finding:

1. set `OPEN -> IN_PROGRESS` before work;
2. validate/reproduce the finding;
3. apply the targeted root-cause fix;
4. add/strengthen regression coverage when meaningful;
5. record exact verification evidence;
6. set `RESOLVED` only after verification passes;
7. if invalid, set `REJECTED_WITH_REASON` with concrete evidence rather than silently ignoring it.

Delete the temporary ledger only after all accepted findings are resolved, required re-review is complete, and verification passes. Never commit it.

---

## 6. Routing Semantics

The workflow does not select a model itself. Resolve through `.ai/MODEL_ROUTER.md`.

Operational intent:

- **clear/localized/reproducible fix** -> Muse route first (`Free -> Go`) with effort proportional to scope;
- **tool/browser-heavy frontend bug** -> Gemini High is often the best route;
- **difficult/ambiguous backend root cause** -> Terra High, Fast OFF;
- **critical concurrency/payment/security/data-integrity bug** -> Sol High critical reasoning/review path, Fast OFF.

### Quality failure is not availability failure

If Muse Free produces a buggy fix, do **not** switch to Muse Go merely because the paid route exists. Free -> Go is an availability fallback. For quality failures, follow diagnosis/escalation rules.

### Escalation trigger

After one meaningful repair attempt fails and the root cause remains unclear, escalate **diagnosis**, not blindly implementation:

`Muse/Gemini repair failed -> Terra High root-cause analysis -> targeted repair`

Use stronger Sol escalation only when the risk is critical or Terra leaves severe unresolved ambiguity.

---

## 7. Failure-Type Heuristics

### Concurrency / Double Booking

Inspect transaction boundaries, uniqueness, locking mode/order, isolation assumptions, stale reads, retries, and whether PostgreSQL atomically enforces the invariant. Reproduce with truly overlapping actors, not sequential mocks.

### Idempotency / Payments

Inspect key scope/uniqueness, replays, state transitions, webhook order, duplicate delivery, partial external-call/DB failure windows, and terminal states. Do not hide duplicate processing with broad catches.

### Kafka / Outbox

Inspect transaction ownership, outbox persistence, event IDs, consumer idempotency, duplicates/order, retry/DLQ behavior, and exactly-once assumptions.

### Database / Flyway

Inspect actual schema/migration order, nullability/defaults, constraints/indexes, backfill assumptions, entity/DDL alignment, and compatibility.

### Frontend State

Inspect request races, stale Signals/computed state, optimistic updates, rollback/reconciliation, loading/error/empty states, reconnects, duplicated subscriptions/requests, and cleanup.

### Security

Inspect authentication, roles, ownership/IDOR, validation, secrets/logging, trust boundaries, webhook verification, and alternate-path privilege escalation.

---

## 8. Anti-Patterns

Do not:

- add arbitrary sleeps/retry loops to hide races;
- catch broad exceptions and return success without a recovery contract;
- remove DB constraints because application code conflicts with them;
- weaken tests to fit incorrect behavior;
- duplicate shared abstractions;
- rewrite a whole subsystem for a localized defect;
- mix speculative cleanup into a targeted repair;
- dismiss a flaky test without investigating its failure mode.

---

## 9. Verification

For each fix:

1. narrow reproduction/regression test;
2. targeted task verification;
3. affected module/service tests;
4. integration/concurrency/browser checks justified by the defect;
5. complete diff inspection;
6. review-ledger evidence update when applicable.

For P0/P1/critical fixes, passing the narrow test is never the final gate.

---

## 10. Next Stage

### If P0/P1 or critical/high-risk behavior was fixed

Route to `.ai/workflows/05-code-review.md` for mandatory independent re-review.

### If localized P2/P3 work is resolved with strong verification

Use targeted re-review when required by the review protocol, then proceed to `.ai/workflows/04-testing-and-qa.md`.

### If the repair is blocked/not converging

Return to supervisor/planning/diagnosis rather than looping indefinitely.

---

## 11. Orchestrated Handoff

When delegation is available, the fixer returns structured state to the supervisor:

```text
STAGE: FIX
TASK: ...
PROVIDER/MODEL/EFFORT: ...
FAST MODE: ...
FINDINGS ADDRESSED: ...
ROOT CAUSE: ...
FILES TOUCHED: ...
REGRESSION TESTS: ...
VERIFICATION: ...
LEDGER STATUS: ...
RE-REVIEW REQUIRED: yes/no + reason
UNRESOLVED RISKS: ...
```

The supervisor launches re-review/QA directly.

### Manual fallback

If delegation is unavailable, provide a Next Stage Handoff with the exact workflow, router-derived model route, branch/task/ledger path, and a ready-to-run prompt.

---

## 12. Completion Criteria for a Repair Pass

A repair pass is complete only when:

- expected contract is explicit;
- root cause is supported by evidence;
- fix is minimal and architecture-compliant;
- regression coverage exists when meaningful;
- targeted and broader required verification passes;
- unrelated user changes are preserved;
- required re-review is completed or ready to begin;
- temporary ledger lifecycle is respected.
