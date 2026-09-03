# Workflow 06: Refactoring Protocol

**Role:** Refactoring Engineer

---

## 1. Goal

Improve structure, readability, duplication, modularity, or maintainability **without changing externally observable behavior**, unless the approved task explicitly defines a behavior change.

Refactoring is not a license to redesign unrelated architecture. Preserve contracts and make structural change incrementally so regressions remain diagnosable.

---

## 2. Core Rules

1. **Behavior preservation is the default contract.** API responses, event payloads, DB semantics, authorization, side effects, state transitions, and frontend behavior must remain unchanged unless the task explicitly says otherwise.
2. **Characterize before changing weakly tested behavior.** If important existing behavior lacks adequate tests, add characterization/regression tests before structural edits.
3. **Separate refactor from feature work.** Do not hide feature changes or bug fixes inside a large refactor. If a defect is discovered, use `.ai/workflows/03-bug-fixing.md` or create a separate task.
4. **Small reversible steps.** Prefer a sequence of understandable transformations over a rewrite.
5. **Preserve public contracts.** Do not casually rename endpoints, DTO fields, event fields, DB columns, environment variables, route paths, or shared APIs.
6. **Do not weaken invariants for elegance.** Simpler-looking code is worse if it reduces transaction, locking, security, or validation guarantees.
7. **Use established abstractions.** Consolidate onto existing shared modules/patterns instead of creating parallel frameworks.
8. **Measure performance claims.** Performance refactors require a realistic reason or measurement; do not trade clarity/correctness for speculative speed.

---

## 3. Before Refactoring

Document or infer from an approved task:

- exact scope;
- structural problem being solved;
- behavior that must remain unchanged;
- affected public/internal contracts;
- critical invariants;
- existing tests that protect behavior;
- missing characterization tests;
- expected simplification or measurable benefit;
- rollback boundary.

For large cross-service or architectural refactors, use an approved planning task and ADR where required. Model selection is governed by `.ai/MODEL_ROUTER.md`.

---

## 4. Safe Refactor Sequence

```text
1. Read task, architecture, ADRs, and relevant AGENTS rules.
2. Inspect callers, tests, persistence/events/contracts around the target.
3. Run current targeted tests to establish a green baseline.
4. Add characterization tests for important unprotected behavior.
5. Apply one coherent structural transformation.
6. Run the narrow relevant tests.
7. Repeat for the next transformation.
8. Run broader affected module/integration checks.
9. Inspect the complete diff for accidental behavior changes.
10. Send the final patch through `.ai/workflows/05-code-review.md`.
11. Pass `.ai/workflows/04-testing-and-qa.md` before completion.
```

Do not wait until the end of a very large rewrite to discover which transformation broke behavior.

---

## 5. Backend Refactoring Checks

Pay special attention to accidental semantic changes in:

- `@Transactional` placement/propagation/read-only flags;
- JPA fetch behavior and entity lifecycle;
- lock acquisition and ordering;
- repository query semantics;
- exception types/error codes/HTTP mapping;
- validation order;
- idempotency key handling;
- outbox/event creation timing;
- retry behavior;
- serialization/record field names;
- Flyway/entity alignment;
- shared `common-*` abstractions.

Moving code between methods/classes can change Spring proxy behavior. Refactoring a method call from inter-bean to self-invocation, or moving annotations, may silently change transaction/security semantics even when code looks equivalent.

---

## 6. Frontend Refactoring Checks

Preserve:

- Signal ownership and update semantics;
- `computed()` dependencies;
- request timing/cancellation/reconciliation;
- input/output contracts;
- route behavior;
- loading/error/empty states;
- accessibility and keyboard interaction;
- WebSocket/subscription/timer lifecycle;
- responsive behavior;
- backend DTO compatibility.

Do not replace a clear local state model with a broader shared store unless the task demonstrates why shared ownership is needed.

---

## 7. Refactoring Tests

Useful evidence includes:

- pre-existing regression tests passing before and after;
- characterization tests added before touching fragile legacy behavior;
- API/serialization contract tests when types/mapping move;
- integration tests when repository/transaction/service boundaries change;
- concurrency tests when lock/transaction code is reorganized;
- frontend interaction/state tests when component/service responsibilities move.

Do not assert only internal structure. Tests should protect the behavior the refactor promises to preserve.

---

## 8. Large Refactor Guardrails

For repo-wide or large cross-file refactors:

- plan the transformation in dependency order;
- avoid simultaneous unrelated renames + logic movement + contract changes;
- keep compatibility adapters temporarily when needed for safe migration;
- verify each module before moving to the next;
- keep commits/review units understandable where the workflow permits;
- explicitly inspect deleted code to confirm no unique behavior was lost;
- compare old/new public contracts and runtime configuration;
- require substantive independent review.

If the implementation becomes easier as a clean rewrite but correctness becomes harder to compare, prefer the safer incremental path.

---

## 9. Completion Criteria

A refactor is complete only when:

- the stated structural problem is actually improved;
- observable behavior is preserved except for explicitly approved changes;
- critical invariants remain intact;
- characterization/regression coverage is adequate for changed boundaries;
- targeted and broader relevant verification passes;
- the final diff contains no unrelated feature work;
- code review is complete and findings are resolved;
- the final QA gate passes;
- no temporary `.ai/tmp/review-*.md` ledger remains.

A refactor that is cleaner but less demonstrably correct is not an improvement.

---

## 10. Next Stage Handoff: Independent Code Review

Once refactoring transformations and self-verification pass, hand off to **Workflow 05: Code Review** (`.ai/workflows/05-code-review.md`).

### 10.1 AI Model & Reasoning Effort Selection

Consult `.ai/MODEL_ROUTER.md`:

| Refactoring Scope | Recommended Route | Alternative Route | Escalation / High Risk |
|---|---|---|---|
| **Substantive Architecture Refactor Review** | **GPT-5.6 Terra High** | **Gemini 3.8 Flash High** | **GPT-5.6 Terra xHigh** |
| **Mechanical / Low-Risk Refactor Review** | **GPT-5.6 Terra Medium** | **Gemini 3.8 Flash Medium** | **GPT-5.6 Terra High** |
| **Critical Domain Refactor Review** (Holds, Locking, Payments, Security) | **GPT-5.6 Sol High** | **GPT-5.6 Terra xHigh** | **GPT-5.6 Sol xHigh** |

### 10.2 Next Stage Prompt Template

Copy and paste the following prompt to invoke the code reviewer:

```markdown
You are the Independent Code Reviewer for SeatFlow reviewing a Refactoring change.
Task/Scope: [Refactoring Scope / Task ID]
Branch: `refactor/<scope>` (or `feat/p<XX>-<YYY>-<task-desc>`)

Instructions:
1. Follow `.ai/workflows/05-code-review.md` and `.ai/workflows/06-refactoring.md`.
2. Inspect the git diff against `develop`:
   `git diff develop...HEAD`
3. Verify that observable behavior is strictly preserved:
   - No accidental contract changes (DTOs, REST endpoints, events, DB schema).
   - Invariant guarantees remain intact.
   - Spring proxy, `@Transactional`, JPA fetch types, and caching behavior are preserved.
   - Angular Signals/computed reactivity semantics remain unchanged.
4. Verify that characterization and existing regression tests pass.
5. If behavioral drift, regressions, or contract breaks are found, create `.ai/tmp/review-<branch-or-task>.md`.
6. Deliver verdict: `APPROVE`, `APPROVE WITH NON-BLOCKING P3 NOTES`, or `CHANGES REQUIRED`.
```
