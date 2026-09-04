# Workflow 02: Task Execution Protocol

**Role:** Builder / Implementer

---

## 1. Goal

Implement one approved SeatFlow task **faithfully, minimally, and verifiably**.

The implementer may resolve local coding details but must not silently redesign architecture, weaken invariants, broaden scope, or become the final reviewer of its own substantive work.

Model/provider/effort selection is governed only by `.ai/MODEL_ROUTER.md`.

---

## 2. Non-Negotiable Rules

1. Follow the task, accepted ADRs, `.ai/architecture/`, `AGENTS.md`, and subsystem `AGENTS.md`.
2. Do not weaken/delete tests to make implementation pass.
3. Avoid unrelated refactoring/style churn.
4. Prefer the smallest correct diff.
5. Preserve API/event/DB/frontend compatibility unless the task explicitly changes it.
6. Treat tests as evidence, not proof, for concurrency/payment/security/idempotency/migrations/distributed state.
7. Never commit `.ai/tmp/` review ledgers.
8. Preserve all pre-existing user working-tree changes outside the task.
9. Do not force-push, reset hard, or discard unrelated work.

---

## 3. Pre-Implementation Check

Before editing:

```text
1. Confirm the dedicated task branch/worktree.
2. Inspect git status and record pre-existing changes.
3. Read the complete task file and orchestration metadata.
4. Read referenced ADRs/architecture docs.
5. Read backend/AGENTS.md and/or frontend/AGENTS.md as applicable.
6. Inspect neighboring implementation and shared abstractions.
7. Confirm migrations/DTOs/endpoints/events/frontend contracts match task assumptions.
8. Identify critical invariants and highest-risk failure modes.
9. Confirm exact verification commands.
```

If repository reality contradicts the task in a way that changes architecture or acceptance criteria, stop deterministic implementation and return the issue to planning/supervisor.

---

## 4. Implementation Sequence

Use the task's explicit sequence first.

Default backend order when the task delegates to repository conventions:

```text
Flyway migration
-> entity / enum / value object
-> repository/query
-> request/response records
-> mapper
-> service interface
-> service implementation
-> controller/adapter
-> messaging/client integration
-> tests
```

Default frontend order:

```text
interfaces/models
-> API/state service
-> shared primitive only when genuinely reusable
-> feature component
-> template/styles
-> routes/guards
-> tests
```

Do not create abstractions before confirming they are required by the task or existing architecture.

---

## 5. Implementation Discipline

While coding:

- preserve transaction boundaries and locking semantics;
- preserve idempotency/retry behavior;
- keep authorization server-side;
- validate untrusted input at the correct boundary;
- keep DB constraints aligned with application invariants;
- preserve established time/money types and semantics;
- use transactional outbox for domain events where required;
- use Eureka/LoadBalancer client patterns required by the constitution;
- update `.env.example` only when a new version-controlled variable is introduced;
- never log secrets/payment credentials/unnecessary PII;
- keep frontend state transitions explicit and reconcile authoritative server state;
- add meaningful tests alongside behavior, not as cleanup after coding.

If implementation reveals an unrelated defect, either apply a tiny safe prerequisite fix with regression coverage or create/route a separate bug task. Do not smuggle broad cleanup into the feature.

---

## 6. Self-Verification

Run checks in this order:

1. task's exact verification command;
2. new/modified regression tests;
3. affected module/service suite;
4. build/compile/typecheck/lint as applicable;
5. integration/Testcontainers/concurrency/browser checks justified by risk;
6. manual `git diff` inspection;
7. acceptance-criteria evidence review.

Also inspect for:

- accidental TODO/FIXME/debug logs;
- dead code/imports;
- stale comments;
- unrelated formatting churn;
- secrets/local `.env` files;
- API/event/frontend contract drift.

A green suite does not justify skipping diff inspection.

---

## 7. Required Implementation Output

Return structured evidence:

```text
STAGE: IMPLEMENT
TASK: TASK-PXX-YYY
BRANCH/WORKTREE: ...
PROVIDER: ...
MODEL: ...
EFFORT: ...
FAST MODE: OFF | ON | N/A
STATUS: PASS | BLOCKED | FAIL
FILES TOUCHED: ...
KEY CHANGES: ...
VERIFICATION RUN: command -> result
PRE-EXISTING CHANGES PRESERVED: yes/no + details
UNRESOLVED RISKS: ...
NEXT STAGE: INDEPENDENT REVIEW
```

Do not claim implementation complete if required verification failed.

---

## 8. Next Stage: Independent Review

After self-verification passes, route to `.ai/workflows/05-code-review.md`.

### Orchestrated mode

The supervisor must:

1. resolve an **independent reviewer** via `.ai/MODEL_ROUTER.md`;
2. apply Poracode rules from `.ai/integrations/PORACODE.md` when applicable;
3. pass the original task, architecture/ADR context, complete diff, changed tests, verification results, and known risks;
4. ensure the implementation agent does not self-approve.

For ordinary Muse implementation, the default substantive reviewer route is Codex/Terra High with Fast OFF; critical domains follow the Sol risk override. The exact selection still comes from `.ai/MODEL_ROUTER.md`.

### Manual fallback

If delegation is unavailable, return a Next Stage Handoff with:

- workflow: `.ai/workflows/05-code-review.md`;
- recommended/fallback/alternative/escalation from `.ai/MODEL_ROUTER.md`;
- copy-paste review prompt containing task ID/path, branch, diff command, verification evidence, and review focus.

---

## 9. Review Findings

If review creates `.ai/tmp/review-<task-or-branch>.md`:

1. preserve it until accepted findings are resolved;
2. route fixes through `.ai/workflows/03-bug-fixing.md`;
3. re-review when required by severity/risk;
4. delete the ledger only after repair verification/re-review completes;
5. never commit the ledger.

---

## 10. Completion Gate

Implementation stage can hand off successfully only when:

- task behavior is implemented;
- targeted verification passes;
- implementation diff is inspected;
- no known blocker is hidden;
- unrelated user changes are preserved;
- independent review is ready to begin.

The overall task is **not** complete until review/fix/re-review and final QA gates pass.
