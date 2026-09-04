# Workflow 06: Refactoring Protocol

**Role:** Refactoring Engineer

---

## 1. Goal

Improve structure, readability, duplication, modularity, or maintainability **without changing externally observable behavior**, unless an approved task explicitly defines a behavior change.

Refactoring is not permission to redesign unrelated architecture.

Model/provider/effort selection is centralized in `.ai/MODEL_ROUTER.md`.

---

## 2. Core Rules

1. Behavior preservation is the default contract.
2. Characterize weakly tested important behavior before structural changes.
3. Do not hide features/bug fixes inside a broad refactor.
4. Prefer small reversible transformations over rewrites.
5. Preserve public API/event/DB/env/route contracts unless explicitly approved.
6. Never weaken transaction/locking/security/validation invariants for elegance.
7. Reuse established shared abstractions instead of creating parallel frameworks.
8. Performance refactors require a concrete reason/measurement.
9. Preserve unrelated user working-tree changes.

---

## 3. Pre-Refactor Contract

Before editing, establish:

- exact scope;
- structural problem being solved;
- behavior that must stay unchanged;
- affected public/internal contracts;
- critical invariants;
- current tests that protect behavior;
- missing characterization tests;
- expected simplification/measurable benefit;
- rollback boundary;
- complexity/risk/verification metadata.

Large cross-service/architectural refactors require planning and an ADR when repository policy says so.

---

## 4. Safe Refactor Sequence

```text
1. Read task, ADRs, architecture, AGENTS rules.
2. Inspect callers/tests/persistence/events/contracts around target.
3. Run current targeted tests to establish a green baseline.
4. Add characterization tests for important unprotected behavior.
5. Apply one coherent structural transformation.
6. Run narrow relevant tests.
7. Repeat incrementally.
8. Run broader module/integration checks.
9. Inspect the complete diff for accidental behavior changes.
10. Send patch through independent review.
11. Resolve findings/re-review.
12. Pass final QA.
```

Do not wait until the end of a large rewrite to discover which transformation changed behavior.

---

## 5. Backend Refactoring Risk Checks

Pay special attention to accidental changes in:

- `@Transactional` placement/propagation/read-only behavior;
- Spring proxy/self-invocation semantics;
- JPA fetch/entity lifecycle;
- lock acquisition/order;
- repository query semantics;
- error/status mapping;
- validation order;
- idempotency handling;
- outbox/event timing;
- retry behavior;
- serialization/record field names;
- Flyway/entity alignment;
- shared `common-*` contracts.

Moving code between methods/classes can change Spring proxy/transaction/security behavior even when logic looks equivalent.

---

## 6. Frontend Refactoring Risk Checks

Preserve:

- Signal ownership/update semantics;
- `computed()` dependencies;
- request timing/cancellation/reconciliation;
- input/output contracts;
- routes/guards;
- loading/error/empty behavior;
- accessibility/keyboard behavior;
- WebSocket/subscription/timer lifecycle;
- responsive behavior;
- backend DTO compatibility.

Do not replace clear local state with a broader store without an approved architectural reason.

---

## 7. Refactoring Evidence

Useful evidence includes:

- existing tests green before and after;
- characterization tests added before fragile changes;
- API/serialization contract tests when types/mapping move;
- integration tests when repository/transaction/service boundaries move;
- concurrency tests when lock/transaction code is reorganized;
- frontend interaction/state tests when responsibilities move.

Do not assert only internal structure. Protect the behavior the refactor promises to preserve.

---

## 8. Large Refactor Guardrails

For large/repo-wide refactors:

- plan dependency order;
- avoid mixing renames + logic movement + contract change at once;
- keep compatibility adapters temporarily where useful;
- verify each module before moving on;
- keep review units understandable;
- inspect deleted code for unique behavior;
- compare old/new public contracts/configuration;
- require substantive independent review.

If a clean rewrite is easier to write but harder to prove equivalent, prefer the safer incremental path.

---

## 9. Model Routing Semantics

Resolve through `.ai/MODEL_ROUTER.md`.

Operational intent:

- mechanical refactor execution -> Muse High (`Free -> Go`);
- substantive/repo-wide deterministic refactor -> Muse xHigh (`Free -> Go`);
- frontend/browser-heavy refactor -> Gemini High is a strong alternative/default when visual tooling dominates;
- architecture-heavy planning -> Terra High or Gemini High according to dominant risk;
- substantive independent review -> Terra High, Fast OFF;
- critical-domain refactor review -> Sol High, Fast OFF.

A failed refactor test is a quality/debugging event, not a reason to switch Muse Free -> Go unless Free is actually unavailable.

---

## 10. Completion Criteria

A refactor is complete only when:

- stated structural problem is materially improved;
- observable behavior is preserved except approved changes;
- critical invariants remain intact;
- characterization/regression coverage is adequate;
- targeted/broader verification passes;
- no unrelated feature work entered the diff;
- independent review completed;
- accepted findings resolved/re-reviewed as required;
- final QA passes;
- no temporary review ledger remains.

Cleaner code with weaker evidence of correctness is not an improvement.

---

## 11. Orchestrated Next Stage

After refactor self-verification, supervisor routes to `.ai/workflows/05-code-review.md` using an independent reviewer selected by `.ai/MODEL_ROUTER.md`.

Pass:

- refactor objective;
- behavior-preservation contract;
- branch/worktree;
- full diff;
- before/after verification evidence;
- characterization tests;
- relevant backend/frontend risk checklist.

If findings exist, route through `.ai/workflows/03-bug-fixing.md`; then re-review as required and pass `.ai/workflows/04-testing-and-qa.md`.

### Manual fallback

If delegation is unavailable, return a complete Next Stage Handoff with the router-derived reviewer model and a ready-to-run review prompt.

---

## 12. Refactor Output

```text
STAGE: REFACTOR
TASK/SCOPE: ...
PROVIDER/MODEL/EFFORT: ...
FAST MODE: ...
BEHAVIOR CONTRACT: ...
TRANSFORMATIONS: ...
FILES TOUCHED: ...
CHARACTERIZATION/REGRESSION TESTS: ...
VERIFICATION: ...
UNRESOLVED RISKS: ...
NEXT STAGE: INDEPENDENT REVIEW
```
