# Workflow 04: Testing, QA & Final Quality Gate

**Role:** QA / Test Engineer

---

## 1. Goal

Verify that a SeatFlow change is not only green on its happy path but robust against realistic regressions, contract drift, concurrency mistakes, unsafe migrations, frontend state races, security mistakes, and incomplete review cleanup.

QA is the **final completion gate** after implementation, fixing, and independent review.

Provider/model/effort selection is centralized in `.ai/MODEL_ROUTER.md`.

---

## 2. Testing Principle

Use the lowest-cost layer that can **actually prove the behavior**.

```text
E2E / cross-service
integration / Testcontainers
concurrency / failure tests
slice / contract tests
unit tests
```

Do not use a mocked unit test as evidence for a property that depends on PostgreSQL locking, Kafka delivery, Stripe/webhook semantics, browser behavior, WebSocket reconnects, or real serialization/contracts.

---

## 3. Backend Test Standards

### Unit

Use JUnit/Mockito where isolation is appropriate. Verify meaningful outputs/state/interactions, not merely "does not throw".

### Slice / contract

Use focused repository/controller/security/serialization tests when they provide a real oracle.

### Integration

Use real PostgreSQL/Testcontainers for:

- constraints;
- transactions;
- locks;
- Flyway behavior;
- DB-specific queries.

Use real Kafka/Testcontainers when delivery/serialization/idempotency/order is the behavior under test.

### Concurrency

For reservation/seat ownership/hold invariants:

- synchronize contenders deterministically;
- ensure true overlap;
- assert domain outcome, not only thread completion;
- use realistic contention counts where practical;
- never use arbitrary `sleep()` as the primary synchronization technique.

---

## 4. Frontend Test Standards

For Angular changes verify applicable:

- Signal/computed state updates;
- loading/error/empty/success/disabled states;
- user interaction/form validation;
- API request/response shape;
- stale request and async race handling;
- optimistic rollback/reconciliation;
- route/guard UX behavior;
- WebSocket/reconnect behavior;
- resource/timer/subscription cleanup;
- responsive/visual interaction at relevant breakpoints.

Backend authorization remains authoritative even when frontend guards are tested.

---

## 5. Risk-to-Evidence Matrix

| Risk | Minimum useful evidence |
|---|---|
| Validation/boundary | unit + controller/contract where relevant |
| DB constraints/mapping | real PostgreSQL integration test |
| Transaction boundary | integration evidence + review |
| Double booking/locking | deterministic concurrency test + DB invariant |
| Idempotency | repeated/parallel request tests + persisted-state assertion |
| Kafka duplicate delivery | consumer idempotency/integration evidence |
| Payment/webhook state | transition + duplicate/out-of-order scenarios |
| Authorization/ownership | backend security/controller integration evidence |
| Migration safety | Flyway against representative schema/data where practical |
| Frontend async race | controlled async test + browser check where needed |
| WebSocket reconnect | reconnect + authoritative-state reconciliation |

Tests are evidence; independent review remains required for hidden failure modes that tests cannot exhaustively prove.

---

## 6. QA Preconditions

Before final QA:

1. implementation/self-verification completed;
2. independent review completed for substantive changes;
3. all accepted blocking findings resolved;
4. required re-review completed;
5. temporary review ledger is either already removed or ready for final cleanup because every accepted finding is resolved.

QA must fail if an active task still has unresolved review findings.

---

## 7. Verification Sequence

Run in increasing scope:

```text
1. Exact task verification command.
2. New/modified regression tests.
3. Relevant module/service test suite.
4. Build / compile / typecheck / lint.
5. Relevant integration / Testcontainers / concurrency tests.
6. Relevant browser/manual interaction checks.
7. Broader repository checks required by changed contracts or CI surface.
```

Use actual repository scripts/POM/package configuration. Do not invent commands when authoritative commands already exist.

If a required check cannot run, report the concrete reason and how much confidence is lost.

---

## 8. Test Quality Review

Inspect the tests themselves for weak or misleading oracles:

- non-null/2xx-only assertions;
- mocks that simply return the expected answer;
- missing negative/boundary cases;
- tests that never execute changed branches;
- swallowed exceptions;
- timing-based flakiness;
- H2/in-memory tests for PostgreSQL-specific behavior;
- concurrency tests without real overlap;
- frontend tests coupled only to implementation details.

More tests are not better if they do not detect realistic regressions.

---

## 9. Final Quality Gate

### Correctness and scope

- every acceptance criterion is satisfied;
- critical invariants remain intact;
- no unrelated behavior/refactor entered the diff;
- API/event/DB/frontend contracts align;
- env/documentation changes are present when required.

### Tests and build

- targeted tests pass;
- required regression/integration/concurrency checks pass;
- build/typecheck/lint passes;
- new tests have meaningful failure-detection value.

### Review

- independent review completed where required;
- blocking findings resolved;
- high-risk fixes re-reviewed;
- no active unresolved review ledger remains.

### Diff hygiene

Inspect final diff for:

- TODO/FIXME/debug instrumentation;
- commented-out production code;
- dead imports/files;
- broad formatting churn;
- generated/binary artifacts that should not be committed;
- secrets/credentials/tokens/local `.env`;
- stale docs/comments that contradict behavior.

### Migration / operational safety

When applicable verify:

- migration order and compatibility;
- DDL/entity alignment;
- constraints/indexes support invariants;
- destructive assumptions are understood;
- observability needed for the behavior exists without leaking sensitive data.

---

## 10. Model Routing for QA

This workflow does not hardcode the final model. Resolve through `.ai/MODEL_ROUTER.md`.

Operational intent:

- normal final QA -> Gemini 3.8 Flash High is usually sufficient and cost-effective;
- subtle backend hidden semantics -> Terra High may be preferred;
- critical money/security/concurrency/data-integrity judgment -> Sol High when required by risk;
- if the implementer was Gemini and independence matters, prefer Codex for the judgment-heavy QA/review layer.

All Codex QA/review stages use Fast OFF unless the user explicitly asks otherwise.

---

## 11. Review-Ledger Cleanup

Correct lifecycle:

```text
review
-> findings ledger
-> fixes
-> verification
-> re-review when required
-> all accepted findings resolved
-> delete ledger
-> final QA PASS
```

Never delete the ledger early merely to make QA look clean.

QA must fail completion if the ledger still represents unresolved/unfinished repair work.

---

## 12. QA Output

Return:

```text
STAGE: FINAL QA
TASK: ...
PROVIDER/MODEL/EFFORT: ...
FAST MODE: ...
CHECKS EXECUTED: command -> result
CHECKS NOT EXECUTED: reason + confidence impact
CRITICAL INVARIANTS VERIFIED: ...
DIFF HYGIENE: PASS/FAIL
REVIEW STATE: ...
REMAINING RISKS: ...
VERDICT: PASS | PASS WITH NON-BLOCKING NOTES | FAIL
```

Use `PASS WITH NON-BLOCKING NOTES` only for genuine low-risk observations that do not violate task/architecture/security/data-integrity/correctness requirements.

---

## 13. Next Stage

### PASS / PASS WITH NON-BLOCKING NOTES

Proceed to finalization under `AGENTS.md` and `.ai/ORCHESTRATOR.md`:

- ensure no active review ledger remains;
- move the task file to the correct completed folder;
- perform commit/push/PR operations only when requested/allowed;
- report final Git state.

### FAIL

Route back to `.ai/workflows/03-bug-fixing.md` with the exact QA failure evidence.

The supervisor selects the repair/diagnosis model through `.ai/MODEL_ROUTER.md` rather than using a QA-file model table.

### Manual fallback

If orchestration is unavailable, provide a complete Next Stage Handoff with the router-derived model and exact failure/finalization context.
