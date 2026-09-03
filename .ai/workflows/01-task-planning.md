# Workflow 01: Task Planning & Breakdown Protocol

**Role:** System Architect & Planner

---

## 1. Goal & Responsibilities

The Architect/Planner transforms high-level requirements into **atomic, unambiguous, deterministic task files** that implementation agents can execute without inventing architecture.

A good task must make the intended behavior, failure modes, constraints, files, tests, and verification observable before implementation starts.

---

## 2. Planning Principles

### 2.1 Zero-Ambiguity Rules

1. **One Task = One Atomic Feature Slice:** Prefer one microservice or one frontend domain and a bounded file set. Typical implementation tasks should touch roughly 4-10 meaningful files unless the architecture genuinely requires more.
2. **Explicit Contracts Over General Descriptions:** Specify exact HTTP methods, paths, request/response records, validation rules, status codes, event payloads, persistence changes, and state transitions.
3. **Pre-designed Data Changes:** For schema work, define exact Flyway DDL, nullability, constraints, indexes, migration ordering, and compatibility expectations.
4. **Invariants Must Be Written Down:** Every relevant business, security, consistency, concurrency, idempotency, money, or time invariant belongs in the task file.
5. **Failure Modes Before Implementation:** Identify the most dangerous ways the feature could fail, including boundary cases, partial failures, retries, races, stale state, duplicate events, authorization mistakes, and API-contract drift.
6. **Deterministic Verification:** Every task must specify exact targeted verification commands and the expected observable result.
7. **Reviewability:** The task must give an independent reviewer enough information to determine whether the implementation is correct without reconstructing the intended design from scratch.

### 2.2 Task Scoping & Naming

- **Task ID:** `TASK-P<XX>-<YYY>`
- **Task file:** `.ai/tasks/phase-XX-<phase-name>/<YYY>-<task-description>.md`
- **Completed path:** `.ai/tasks/completed/phase-XX-<phase-name>/<YYY>-<task-description>.md`
- **Feature branch:** `feat/p<XX>-<YYY>-<task-description>`
- Task numbering restarts at `001` for each phase.

Do not hide multiple unrelated behaviors inside one task merely to reduce task count. Split a task when parts can be implemented, verified, rolled back, or reviewed independently.

---

## 3. Risk Profile & Review Requirements

Before writing implementation steps, classify the task:

- **Complexity:** 1-5
- **Failure risk:** Low / Medium / High / Critical
- **Verification strength:** Strong / Partial / Weak
- **Affected invariants:** list exact invariants
- **Primary failure modes:** list realistic failure scenarios
- **Required review depth:** Normal / Substantive / Critical

A task is **Critical** when a hidden mistake can cause money loss, security exposure, corrupted state, double booking, broken idempotency, irreversible data damage, or distributed-consistency failure.

For critical tasks, explicitly require review of the relevant high-risk area after implementation and verification. Model selection is governed by `.ai/MODEL_ROUTER.md`.

---

## 4. Architecture Decision Records (ADRs)

### 4.1 Create an ADR Before Task Breakdown When

1. Introducing a new architectural pattern such as Transactional Outbox, CQRS, Saga orchestration, or a new consistency model.
2. Choosing between competing technologies or libraries with meaningful trade-offs.
3. Modifying a system-wide invariant or policy such as hold TTL, seat limits, authorization policy, retry semantics, or idempotency behavior.
4. Changing contracts in shared modules such as `common-security`, `common-domain`, `common-events`, or `common-observability`.
5. Making a non-trivial storage, migration, messaging, security, or distributed-systems trade-off that future engineers need to understand.

### 4.2 Do Not Create an ADR For

- standard CRUD following established architecture;
- routine bug fixes that preserve existing contracts;
- mechanical refactors;
- routine UI layout/styling changes.

### 4.3 ADR Contents

Use `.ai/decisions/ADR-000-template.md` and document:

- Context
- Decision
- Alternatives Considered
- Consequences
- Implementation Notes
- affected tasks and architecture docs

Set the ADR to `ACCEPTED` before tasks rely on it as an authoritative decision.

---

## 5. Required Task Contents

Every implementation task should contain, where applicable:

1. **Objective** — one precise outcome.
2. **Critical invariants** — behavior that must never be violated.
3. **Dependencies / prerequisites** — existing code, ADRs, services, migrations, endpoints, events.
4. **Exact file inventory** — `[NEW]`, `[MODIFY]`, `[DELETE]` with paths.
5. **Contracts** — DTOs, service methods, HTTP status codes, event schemas, DB changes, frontend state contracts.
6. **Implementation sequence** — deterministic order with no architectural guesswork.
7. **Negative and edge cases** — not only happy path.
8. **Concurrency / idempotency / retry requirements** when applicable.
9. **Security requirements** — authentication, authorization, ownership checks, validation, sensitive logging.
10. **Observability requirements** when behavior needs logs, metrics, tracing, or alerts.
11. **Test requirements** — exact behavior each required test proves.
12. **Verification commands** — targeted commands first; broader module/system checks when required.
13. **Review focus** — files, invariants, state transitions, or failure modes the reviewer must inspect closely.
14. **Acceptance criteria** — independently verifiable completion conditions.

Do not prescribe unnecessary implementation details when an existing repository standard already defines them. Reference `backend/AGENTS.md`, `frontend/AGENTS.md`, architecture docs, and ADRs instead of duplicating them.

---

## 6. Test Planning Rules

Tests must be designed from risks, not generated only from implementation structure.

For relevant tasks, require coverage for:

- happy path;
- validation and boundary values;
- authorization/ownership failures;
- duplicate/idempotent requests;
- invalid state transitions;
- persistence constraints;
- retries and partial failures;
- concurrency/races;
- API or event contract compatibility;
- frontend loading/error/empty/reconnect state;
- regression scenarios for previously fixed bugs.

For critical invariants, state what must be proven by integration or concurrency tests rather than accepting mocks as sufficient evidence.

---

## 7. Planning Sequence

```text
1. Read the relevant `.ai/architecture/` documents and ADRs.
2. Inspect the existing implementation and shared modules before designing new abstractions.
3. Determine whether an ADR is required. Create it first when necessary.
4. Classify complexity, risk, verification strength, affected invariants, and failure modes.
5. Select the phase folder and next 3-digit task number.
6. Copy `.ai/tasks/templates/TASK_TEMPLATE.md`.
7. Define objective, exact contracts, file inventory, implementation order, tests, verification, and review focus.
8. Check that another engineer/agent could implement the task without making architectural decisions.
9. Check that another reviewer could determine correctness from the task + diff + repository contracts.
10. Set status to `READY FOR IMPLEMENTATION` only when both checks pass.
```

Current phase directories include `phase-00` through `phase-17`; use the existing repository structure rather than inventing a new phase name when one already applies.

---

## 8. Planning Quality Gate

A task is not ready if any of these are true:

- important behavior is described with words such as "properly", "appropriately", "handle errors", or "add validation" without exact rules;
- the task changes an invariant without an ADR;
- verification consists only of "tests should pass";
- critical failure modes have no corresponding test or review requirement;
- the task requires the implementer to invent DTOs, schema, state transitions, or architecture;
- unrelated refactoring is mixed into a feature without justification;
- compatibility or migration impact is unknown.

When ambiguity remains, resolve it during planning rather than delegating it silently to the implementer.
