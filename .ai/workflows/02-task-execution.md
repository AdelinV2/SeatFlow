# Workflow 02: Task Execution Protocol

**Role:** Builder / Implementer

---

## 1. Goal

The Builder receives one approved task specification and implements it **faithfully, minimally, and verifiably**. The implementer may resolve local coding details, but must not silently redesign architecture, weaken invariants, or expand scope.

The task is not complete merely because code compiles or targeted tests pass. It must remain reviewable and survive the independent review + QA gates defined in workflows 05 and 04.

---

## 2. Non-Negotiable Rules

1. **Do not improvise architecture.** Follow the task, relevant ADRs, `.ai/architecture/`, and repository AGENTS files.
2. **Respect subsystem standards.**
   - Backend: `backend/AGENTS.md`
   - Frontend: `frontend/AGENTS.md`
3. **Do not weaken tests to make implementation pass.** Never delete, disable, broaden assertions, or replace meaningful integration behavior with mocks just to get green output.
4. **No unrelated refactoring.** If unrelated cleanup is genuinely required, keep it minimal or move it to a separate refactor task.
5. **Prefer the smallest correct diff.** Avoid speculative abstractions, duplicate helpers, or style churn outside the task.
6. **Preserve compatibility unless the task explicitly changes it.** This includes API contracts, event schemas, DB semantics, environment variables, and frontend/back-end integration.
7. **Treat tests as evidence, not proof.** Concurrency, payments, security, idempotency, migrations, and distributed state require explicit reasoning in addition to green tests.
8. **Never commit temporary review files.** `.ai/tmp/` is local scratch state only.

---

## 3. Pre-Implementation Check

Before editing:

```text
1. Checkout the dedicated task branch from `develop`.
2. Read the full assigned task file.
3. Read all referenced ADRs and architecture docs.
4. Read `backend/AGENTS.md` or `frontend/AGENTS.md` as applicable.
5. Inspect existing neighboring code and shared abstractions before creating new ones.
6. Confirm dependent migrations, DTOs, endpoints, events, or frontend models actually match the task assumptions.
7. Identify the task's critical invariants and highest-risk failure modes.
```

If the repository contradicts the task in a way that changes architecture or acceptance criteria, stop treating the task as deterministic implementation: surface the inconsistency for planning rather than silently choosing one interpretation.

---

## 4. Implementation Sequence

Use the task's explicit file inventory and sequence. Where the task delegates to repository standards, use these defaults.

### Backend

```text
Flyway migration
→ Entity / Enum / Value Object
→ Repository
→ Request / Response Records
→ Mapper
→ Service Interface
→ Service Implementation
→ Controller / Adapter
→ Messaging / Client integration when applicable
→ Tests
```

### Frontend

```text
Interfaces / Models
→ API service / state service
→ Shared primitives when genuinely reusable
→ Feature component
→ Template / styles
→ Routes / guards
→ Tests
```

Do not create abstractions before confirming they are required by the task or an established repository pattern.

---

## 5. Implementation Discipline

While coding:

- preserve transaction boundaries and locking semantics;
- preserve idempotency and retry behavior;
- keep authorization server-side where required;
- validate external and user-controlled inputs at the correct boundary;
- keep DB constraints consistent with application invariants;
- ensure time and money logic uses the types and semantics established by the architecture;
- update `.env.example` only when a new version-controlled environment variable is actually introduced;
- avoid logging credentials, tokens, payment details, or unnecessary PII;
- keep frontend state transitions explicit and reconcile authoritative server state after reconnects or failed optimistic updates;
- add tests at the same time as production behavior, not as an afterthought.

If implementation reveals a genuine bug unrelated to the task, do not casually fold a broad fix into the feature. Either apply a tiny safe prerequisite fix with regression coverage or create a separate bug-fix task.

---

## 6. Self-Verification Before Review

Run the task's exact verification command first. Then run the broader checks justified by the changed surface.

Minimum self-check:

```text
1. Targeted tests for the changed behavior.
2. Relevant module/service test suite.
3. Build / typecheck / lint where applicable.
4. Integration or concurrency tests required by the task.
5. Inspect `git diff` manually.
6. Check that every task acceptance criterion has observable evidence.
7. Check for accidental TODO/FIXME/debug logging, dead code, stale comments, and unrelated formatting churn.
8. Check that no secrets or local `.env` files were added.
9. Check that API/event/frontend contracts remain aligned.
```

A green test suite does not justify skipping the diff review.

---

## 7. Next Stage Handoff: Independent Code Review

After self-verification passes, hand the task and branch diff over to **Workflow 05: Code Review** (`.ai/workflows/05-code-review.md`).

Do **not** move the task file to `completed/` before substantive review findings are resolved and the final QA gate passes.

### 7.1 Handling Review Findings

If the reviewer creates `.ai/tmp/review-<task-or-branch>.md`:

1. treat each open finding as explicit work;
2. use `.ai/workflows/03-bug-fixing.md` for bugs/errors and targeted corrections;
3. mark findings resolved only after code + required tests are updated;
4. request re-review of changed areas when the finding is substantive or high risk;
5. keep the temporary review file until every accepted finding is resolved and verification passes;
6. delete the temporary review file before final completion.

Do not delete the file merely to satisfy the cleanup gate.

### 7.2 AI Model & Reasoning Effort Selection

Consult `.ai/MODEL_ROUTER.md` before selecting the reviewer:

| Review Domain | Recommended Route | Alternative Route | Escalation / High Risk |
|---|---|---|---|
| **Substantive Backend Review** | **GPT-5.6 Terra High** | **Gemini 3.8 Flash High** | **GPT-5.6 Terra xHigh** |
| **Routine / Small Review** | **GPT-5.6 Terra Medium** | **Gemini 3.8 Flash High** | **GPT-5.6 Terra High** (if issues detected) |
| **Frontend Review** | **Gemini 3.8 Flash High** | **GPT-5.6 Terra High** | **GPT-5.6 Terra High** (for state/contract risks) |
| **Critical Invariants** (Holds, Double Booking, Money, Security, Idempotency) | **GPT-5.6 Sol High** | **GPT-5.6 Terra xHigh** | **GPT-5.6 Sol xHigh** (if unresolved ambiguity) |

### 7.3 Next Stage Prompt Template

Copy and paste the following prompt to invoke the independent code reviewer:

```markdown
You are the Independent Code Reviewer for SeatFlow.
Review task: [TASK-P<XX>-<YYY>: <Task Title>]
Branch: `feat/p<XX>-<YYY>-<task-desc>`
Task specification: `.ai/tasks/phase-<XX>-<service-name>/<YYY>-<task-desc>.md`

Instructions:
1. Follow `.ai/workflows/05-code-review.md`. Act as an independent, skeptical reviewer.
2. Inspect the git diff against `develop`:
   `git diff develop...feat/p<XX>-<YYY>-<task-desc>`
3. Review surrounding contracts, JPA transactions, API/event schemas, and DB constraints.
4. Verify non-negotiable invariants:
   - Max 10 seats per reservation
   - 15-minute hold TTL & expiration
   - Zero double-booking concurrency guarantee (PostgreSQL source of truth)
   - Transactional outbox pattern & Kafka envelope integrity
   - Idempotency on payment and reservation endpoints
   - Server-side JWT role authorization
5. Evaluate test quality: are edge cases, boundaries, negative paths, and concurrency tested?
6. If any actionable bug, error, invariant issue, or contract gap is found:
   - Create `.ai/tmp/review-p<XX>-<YYY>.md` with the standard template (P0-P3).
7. Finish with a formal verdict: `APPROVE`, `APPROVE WITH NON-BLOCKING P3 NOTES`, `CHANGES REQUIRED`, or `CRITICAL CHANGES REQUIRED`.
```

---

## 8. Completion Gate

The task may be finalized only when:

- implementation matches the approved task and architecture;
- targeted and required broader checks pass;
- code review is complete;
- all accepted review findings are resolved;
- `.ai/tmp/` contains no active review file for the task;
- `.ai/workflows/04-testing-and-qa.md` final quality gate passes;
- task checklist/acceptance criteria are complete;
- the task file is moved to the appropriate `.ai/tasks/completed/...` folder;
- changes are committed using the repository's conventional commit rules and proposed to `develop` through the normal PR workflow.

Completion is a quality decision, not simply the end of code generation.
