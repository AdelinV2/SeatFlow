# SeatFlow Autonomous Engineering Orchestrator

## Purpose

This file is the control plane for autonomous multi-agent engineering in SeatFlow.

It defines **how stages are sequenced and delegated**. It does not replace the detailed stage protocols under `.ai/workflows/`, repository engineering rules in `AGENTS.md`, subsystem rules, architecture specifications, ADRs, or the model-selection policy in `.ai/MODEL_ROUTER.md`.

The intended user experience is simple:

```text
Implement TASK-P12-001 using the SeatFlow autonomous orchestration workflow.
```

When the current harness supports delegation (for example Poracode Crossagents), the supervisor should execute the complete workflow without requiring the user to copy prompts between agents.

---

## 1. Sources of Truth and Precedence

When instructions overlap, use this order:

1. `AGENTS.md` and the nearest subsystem `AGENTS.md`
2. accepted ADRs under `.ai/decisions/`
3. architecture specifications under `.ai/architecture/` and the master spec
4. the active task specification under `.ai/tasks/`
5. this file, `.ai/ORCHESTRATOR.md`
6. the active stage protocol under `.ai/workflows/`
7. `.ai/MODEL_ROUTER.md` for provider/model/effort selection
8. integration-specific execution rules such as `.ai/integrations/PORACODE.md`
9. `.ai/AI_MODEL_REFERENCE.md` only when routing evidence is genuinely needed

If an active task contradicts an accepted ADR or non-negotiable invariant, stop and surface the conflict rather than silently choosing one interpretation.

---

## 2. Execution Modes

### 2.1 Orchestrated Mode — Preferred

Use when the current harness can launch or steer other agents.

The supervisor:

- reads the task and repository instructions;
- classifies risk/complexity/verification strength;
- resolves the correct stage route through `.ai/MODEL_ROUTER.md`;
- delegates each specialist stage;
- passes structured context between stages;
- waits for required gates;
- loops review -> fix -> re-review when needed;
- reports stage transitions and final evidence to the user.

The user must **not** be asked to manually relay prompts between agents when the delegation mechanism is working.

### 2.2 Manual Fallback Mode

Use only when delegation is unavailable, broken, or the user explicitly wants manual control.

In manual fallback mode, the acting agent must provide a structured Next Stage Handoff containing:

- next stage and workflow file;
- recommended provider/model/effort from `.ai/MODEL_ROUTER.md`;
- alternative and escalation route;
- complete copy-paste prompt populated with task, branch, review ledger, and verification context.

---

## 3. Canonical State Machine

```text
REQUEST
  |
  v
UNDERSTAND / CLASSIFY
  |
  +--> architecture ambiguity or task not implementation-ready
  |        |
  |        v
  |      PLAN
  |        |
  +--------+
  |
  v
IMPLEMENT
  |
  v
SELF-VERIFY
  |
  v
INDEPENDENT REVIEW
  |
  +--> APPROVE --------------------------+
  |                                       |
  +--> FINDINGS -> FIX -> VERIFY FIXES    |
                    |                     |
                    v                     |
                 RE-REVIEW                |
                    |                     |
                    +-- findings -> FIX --+
                    |
                    +-- approve ----------+
                                            |
                                            v
                                         FINAL QA
                                            |
                         +------------------+------------------+
                         |                                     |
                       PASS                                  FAIL
                         |                                     |
                         v                                     v
                     FINALIZE                            DIAGNOSE / FIX
                         |                                     |
                         v                                     +--> REVIEW/QA again
                      COMPLETE
```

The canonical implementation quality cycle is:

`plan when needed -> implement -> self-verify -> independent review -> fix -> re-review -> final QA -> finalize`

Do not skip independent review for substantive changes merely because implementation tests pass.

---

## 4. Stage Contracts

### 4.1 UNDERSTAND / CLASSIFY

Before delegation, the supervisor must:

1. inspect current branch and working tree;
2. identify pre-existing user changes and protect them;
3. read the full task file;
4. read referenced ADRs, architecture docs, and relevant `AGENTS.md` files;
5. classify:
   - complexity `1-5`;
   - failure risk `Low | Medium | High | Critical`;
   - context size;
   - agentic demand;
   - verification strength `Strong | Partial | Weak`;
   - affected critical invariants;
6. decide whether the task is implementation-ready.

Do not treat repository documentation as automatically accurate when current implementation evidence contradicts it. Surface material drift to planning/review.

### 4.2 PLAN

Protocol: `.ai/workflows/01-task-planning.md`

Planning is required when:

- the task file does not yet exist;
- architecture or contracts remain ambiguous;
- the task crosses multiple services with non-trivial coupling;
- migrations/state transitions/concurrency/security/payment semantics require design judgment;
- implementation reveals a contradiction that changes acceptance criteria.

Planning may be skipped for an already approved, deterministic atomic task.

### 4.3 IMPLEMENT

Protocol: `.ai/workflows/02-task-execution.md`

The implementation agent must receive:

- task ID and exact task path;
- current branch/worktree;
- relevant plan if one exists;
- authoritative invariants and ADRs;
- known pre-existing working-tree changes;
- exact verification commands.

The implementation agent must not be the final reviewer of its own work.

### 4.4 SELF-VERIFY

The implementer runs the task's deterministic verification first, then the smallest broader checks justified by the changed surface.

Self-verification is evidence for review, not a substitute for review.

### 4.5 INDEPENDENT REVIEW

Protocol: `.ai/workflows/05-code-review.md`

The reviewer must be independent from the implementation attempt. Prefer a different model family/provider where practical, especially when the implementation was non-trivial.

Review inputs must include:

- original task/acceptance criteria;
- relevant ADRs/architecture;
- complete diff against the correct base;
- tests added/changed;
- verification evidence;
- surrounding contracts required to validate behavior.

If actionable findings exist, create/update `.ai/tmp/review-<task-or-branch>.md` and return a blocking verdict when required by the review protocol.

### 4.6 FIX

Protocol: `.ai/workflows/03-bug-fixing.md`

For each accepted finding:

- validate/reproduce when practical;
- fix the root cause, not the symptom;
- add regression coverage when meaningful;
- update the temporary review ledger with evidence;
- rerun targeted verification.

A failed implementation or failed repair does **not** mean a more expensive copy of the same route should automatically be used. Follow `.ai/MODEL_ROUTER.md` escalation triggers.

### 4.7 RE-REVIEW

Mandatory for:

- P0/P1 findings;
- concurrency, payment, security, idempotency, distributed-consistency, or dangerous migration fixes;
- a materially changed implementation approach;
- interacting P2 fixes with meaningful behavioral impact.

Use an independent reviewer and verify both the original finding and nearby regression risk.

### 4.8 FINAL QA

Protocol: `.ai/workflows/04-testing-and-qa.md`

QA validates:

- acceptance criteria;
- relevant tests/build/typecheck/lint;
- integration/concurrency/browser evidence when applicable;
- contract alignment;
- diff hygiene;
- review completion;
- cleanup of temporary review ledgers.

QA must return `PASS`, `PASS WITH NON-BLOCKING NOTES`, or `FAIL`.

### 4.9 FINALIZE

Only after QA passes:

- move the active task file to the matching completed folder;
- verify no active `.ai/tmp/review-*.md` file remains;
- commit using Conventional Commits when the user/task requests repository finalization;
- push/open PR/merge only when allowed by repository rules and explicit user intent.

---

## 5. Model Resolution

The supervisor must **never hardcode a model based only on the stage name**.

For every delegated stage:

1. classify the stage/task using `.ai/MODEL_ROUTER.md`;
2. resolve the logical route there;
3. apply provider-availability and quota fallbacks;
4. apply integration rules from `.ai/integrations/PORACODE.md` when using Poracode;
5. record the actual provider/model/effort used.

`.ai/MODEL_ROUTER.md` is the single source of truth for model selection. Workflow files define behavior, not model catalogs.

---

## 6. Structured Stage Handoff

Each delegated specialist should return enough information for the supervisor to continue without rereading the entire thread.

Recommended handoff shape:

```text
STAGE: IMPLEMENT | REVIEW | FIX | QA | ...
TASK: TASK-PXX-YYY
BRANCH/WORKTREE: ...
PROVIDER: ...
MODEL: ...
EFFORT: ...
FAST MODE: ON | OFF | N/A
STATUS: PASS | FAIL | CHANGES REQUIRED | BLOCKED | ...
FILES TOUCHED: ...
VERIFICATION RUN: ...
KEY FINDINGS / CHANGES: ...
REVIEW LEDGER: path | none
UNRESOLVED RISKS: ...
RECOMMENDED NEXT STAGE: ...
```

Do not rely on vague summaries such as "looks good" or "implementation complete".

---

## 7. Retry and Escalation Policy

Avoid infinite agent loops.

Default limits:

- one normal implementation attempt;
- one normal fix pass for clear findings;
- re-review after substantive fixes;
- one meaningful retry after a failed repair when the root cause is understood;
- escalate/re-plan when the same issue survives or the failure indicates missing design judgment.

Stop and report a blocker when:

- required provider/model access is unavailable and no allowed fallback exists;
- task/architecture contradiction cannot be resolved safely;
- required infrastructure for verification is unavailable and materially limits confidence;
- repeated repair attempts are not converging;
- continuing would require destructive Git/data operations without approval.

---

## 8. Parallelism Policy

Parallel delegation is allowed only for genuinely independent work.

Good candidates:

- read-only repository research;
- independent review perspectives;
- backend and frontend tasks isolated in separate worktrees with stable contracts;
- independent test/contract analysis.

Prefer sequential execution when agents would:

- edit the same files;
- depend on one another's contracts;
- touch the same migration/state machine;
- operate on the same mutable working tree;
- resolve findings from an implementation that is still changing.

When multiple writing agents are used concurrently, isolate them with Git worktrees.

---

## 9. Git and Working-Tree Safety

Never discard user work to make orchestration easier.

Prohibited without explicit user approval:

- `git reset --hard`;
- force push;
- deleting/stashing unrelated user modifications just to obtain a clean tree;
- merging to `develop`/`main` merely because an agent finished;
- destructive migrations/data operations outside an approved task.

Before editing, record pre-existing modified/untracked files. At final QA, confirm they were preserved unless the task explicitly included them.

---

## 10. Typical Autonomous Pipelines

### Approved normal backend task

`UNDERSTAND -> IMPLEMENT -> SELF-VERIFY -> REVIEW -> FIX if needed -> RE-REVIEW if needed -> QA -> FINALIZE`

### Task requiring design work

`UNDERSTAND -> PLAN -> IMPLEMENT -> SELF-VERIFY -> REVIEW -> FIX/RE-REVIEW -> QA -> FINALIZE`

### Critical reservation/payment/security task

`UNDERSTAND -> CRITICAL RISK ANALYSIS/PLAN -> IMPLEMENT -> EXHAUSTIVE VERIFY -> CRITICAL REVIEW -> FIX/RE-REVIEW -> QA -> FINALIZE`

### Reproducible bug

`UNDERSTAND -> REPRODUCE -> FIX -> VERIFY -> REVIEW when substantive/high-risk -> QA`

### Refactor

`UNDERSTAND -> CHARACTERIZE -> REFACTOR -> VERIFY -> INDEPENDENT REVIEW -> FIX if needed -> QA`

---

## 11. Completion Output

The supervisor's final response should be concise but auditable:

- task and final status;
- stages executed;
- provider/model/effort actually used per stage;
- whether Codex Fast mode was used;
- files/modules changed;
- tests/builds/verification executed;
- review verdict and resolved findings;
- remaining non-blocking risks/notes;
- Git/PR/finalization result when applicable.

A task is not complete because all agents stopped. It is complete only when the repository quality gates say it is complete.
