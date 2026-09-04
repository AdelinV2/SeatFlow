# Workflow 01: Task Planning & Breakdown Protocol

**Role:** System Architect / Planner

---

## 1. Goal

Transform a high-level requirement into an **atomic, unambiguous, deterministic task specification** that another implementation agent can execute without inventing architecture.

A good task makes intended behavior, contracts, failure modes, file scope, tests, verification, and review focus explicit before coding begins.

This workflow defines planning behavior only. Provider/model/effort selection is always resolved through `.ai/MODEL_ROUTER.md`.

---

## 2. When Planning Is Required

Use this workflow when:

- no approved task file exists;
- architecture/contracts remain ambiguous;
- multiple services/components must be coordinated non-trivially;
- persistence/messaging/security/concurrency/payment semantics require design judgment;
- implementation discovers a contradiction that changes acceptance criteria;
- a refactor changes structural boundaries enough to need a planned sequence.

An already approved deterministic task may proceed directly to `.ai/workflows/02-task-execution.md`.

---

## 3. Planning Principles

### 3.1 Atomic scope

Prefer one coherent feature slice per task. Typical tasks should touch a bounded set of meaningful files unless the architecture genuinely requires more.

Split work when parts can be implemented, verified, reviewed, rolled back, or released independently.

### 3.2 Explicit contracts

Specify exact behavior where applicable:

- HTTP method/path/status/body;
- DTO fields/types/validation;
- service/repository signatures;
- event topic/payload/envelope;
- DB columns/nullability/defaults/constraints/indexes;
- state transitions;
- authorization/ownership rules;
- idempotency/retry semantics;
- frontend state/request/reconnect behavior.

Do not use vague requirements such as "handle errors properly" or "add validation" without defining observable rules.

### 3.3 Invariants first

Write down every relevant business/security/consistency/concurrency/money/time invariant.

Critical SeatFlow invariants from `AGENTS.md` remain authoritative.

### 3.4 Failure modes before implementation

Identify realistic ways the feature could fail:

- boundaries and invalid input;
- stale state and lost updates;
- races/locking/order;
- retries/duplicates/idempotency;
- partial failures;
- authorization mistakes;
- migration/backfill problems;
- API/event/frontend contract drift;
- reconnect/async UI races.

### 3.5 Deterministic verification

Every task must specify the narrowest useful verification command plus broader checks required by the changed risk surface.

"Tests should pass" is not a verification plan.

---

## 4. Task Identity

- Task ID: `TASK-P<XX>-<YYY>`
- Active file: `.ai/tasks/phase-XX-<phase-name>/<YYY>-<task-description>.md`
- Completed file: `.ai/tasks/completed/phase-XX-<phase-name>/<YYY>-<task-description>.md`
- Branch: `feat/p<XX>-<YYY>-<task-description>`

Task numbering restarts at `001` for each phase.

Use `.ai/tasks/templates/TASK_TEMPLATE.md`.

---

## 5. Risk / Orchestration Metadata

Before writing implementation details classify:

- **Complexity:** `1-5`
- **Failure risk:** `Low | Medium | High | Critical`
- **Verification strength:** `Strong | Partial | Weak`
- **Required review depth:** `Normal | Substantive | Critical`
- **Affected critical invariants:** exact list
- **Preferred workflow:** `standard | critical | bugfix | refactor`

A task is `Critical` when a hidden mistake can plausibly cause money loss, security exposure, corrupted state, double booking, broken idempotency, destructive data damage, or distributed-consistency failure.

Do **not** hardcode a provider/model into the task metadata. Routing changes over time and is resolved at execution through `.ai/MODEL_ROUTER.md`.

---

## 6. ADR Rules

Create an ADR before task breakdown when the work:

1. introduces a new architectural pattern;
2. chooses between meaningful competing technologies/libraries;
3. changes a global/domain invariant;
4. changes a shared `backend/common/` contract;
5. makes a non-trivial storage/migration/messaging/security/distributed-systems trade-off.

Do not create ADRs for routine CRUD, normal bug fixes preserving contracts, mechanical refactors, or routine styling.

Use `.ai/decisions/ADR-000-template.md` and set the ADR to an accepted state before dependent implementation treats it as authoritative.

---

## 7. Required Task Contents

Every implementation-ready task should contain, where applicable:

1. objective;
2. orchestration/risk metadata;
3. critical invariants;
4. dependencies/prerequisites;
5. exact `[NEW] / [MODIFY] / [DELETE]` file inventory;
6. API/DTO/service/event/DB/frontend contracts;
7. deterministic implementation sequence;
8. negative/boundary cases;
9. concurrency/idempotency/retry requirements;
10. security/authorization/ownership requirements;
11. observability requirements when needed;
12. exact test requirements and what each proves;
13. verification commands;
14. review focus;
15. independently verifiable acceptance criteria.

Reference existing repository standards instead of duplicating them when a rule already exists in `AGENTS.md`, subsystem `AGENTS.md`, ADRs, or architecture specs.

---

## 8. Test Planning

Design tests from failure risk, not from implementation structure alone.

Require applicable coverage for:

- happy path;
- validation/boundaries;
- authorization/ownership failures;
- duplicate/idempotent requests;
- invalid state transitions;
- persistence constraints;
- retries/partial failures;
- concurrency/races;
- API/event serialization compatibility;
- frontend loading/error/empty/reconnect behavior;
- regression scenarios for known defects.

For critical invariants, state which properties require real PostgreSQL/Testcontainers/concurrency/integration evidence rather than mocks.

---

## 9. Planning Sequence

```text
1. Read AGENTS.md and relevant subsystem AGENTS.md.
2. Read relevant architecture specs and accepted ADRs.
3. Inspect current implementation/shared abstractions before designing new ones.
4. Decide whether a new ADR is required; create/accept it first when necessary.
5. Classify complexity/risk/verification/review depth/invariants.
6. Select the correct phase and next task number.
7. Copy TASK_TEMPLATE.md.
8. Define objective, contracts, file inventory, implementation order, tests, verification, and review focus.
9. Check that an implementer could execute without making architecture decisions.
10. Check that an independent reviewer could judge correctness from task + diff + repository contracts.
11. Set status to READY FOR IMPLEMENTATION only when both checks pass.
```

---

## 10. Planning Quality Gate

A task is not implementation-ready if:

- important behavior remains vague;
- the task changes an invariant without the required ADR;
- schema/DTO/state transition decisions are left to the implementer;
- verification is only "run tests";
- critical failure modes have no evidence/review requirement;
- compatibility/migration impact is unknown;
- unrelated work is bundled without justification;
- repository implementation materially contradicts assumptions and the conflict is unresolved.

Resolve ambiguity during planning rather than transferring it silently to implementation.

---

## 11. Next Stage: Implementation

When the planning gate passes, next stage is `.ai/workflows/02-task-execution.md`.

### Orchestrated mode

The supervisor must:

1. resolve the implementation route through `.ai/MODEL_ROUTER.md`;
2. apply `.ai/integrations/PORACODE.md` if Poracode is active;
3. delegate implementation directly;
4. pass task path, branch/worktree, accepted plan, invariants, pre-existing user changes, and verification commands.

Do not ask the user to copy/paste a handoff when Crossagents/delegation is functioning.

### Manual fallback

If delegation is unavailable, return a Next Stage Handoff containing:

- `Workflow: .ai/workflows/02-task-execution.md`
- recommended/fallback/alternative/escalation from `.ai/MODEL_ROUTER.md`
- a complete implementation prompt populated with the exact task path and branch.

---

## 12. Planner Output

Finish with:

- task ID/path/status;
- risk metadata;
- ADRs created/used;
- important contracts/invariants;
- verification commands;
- unresolved assumptions (must be empty for `READY FOR IMPLEMENTATION` unless explicitly documented/non-blocking);
- orchestrated next-stage delegation result or manual fallback handoff.
