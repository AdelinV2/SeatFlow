# SeatFlow — Global Engineering & Architecture Constitution

This document is the **Global Engineering Constitution** for SeatFlow. It defines the operating model, non-negotiable architecture/business invariants, Git rules, quality gates, and AI-engineering policy.

The authoritative technical blueprint lives in `.ai/SeatFlow-Architecture-and-Implementation-Spec.md` and `.ai/architecture/`.

---

## 1. Autonomous Engineering Operating Model

SeatFlow uses a modular engineering pipeline:

```text
UNDERSTAND / CLASSIFY
        |
        +--> PLAN when architecture/task ambiguity exists
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
        +--> findings -> FIX -> RE-REVIEW
        |
        v
FINAL QA
        |
        v
FINALIZE / COMPLETE
```

Detailed orchestration semantics live in [`.ai/ORCHESTRATOR.md`](.ai/ORCHESTRATOR.md).

Stage protocols:

- Planning: `.ai/workflows/01-task-planning.md`
- Implementation: `.ai/workflows/02-task-execution.md`
- Bug fixing/debugging: `.ai/workflows/03-bug-fixing.md`
- Testing/QA: `.ai/workflows/04-testing-and-qa.md`
- Independent review: `.ai/workflows/05-code-review.md`
- Behavior-preserving refactoring: `.ai/workflows/06-refactoring.md`

Model/provider/effort selection is centralized in [`.ai/MODEL_ROUTER.md`](.ai/MODEL_ROUTER.md).

Poracode/Crossagents execution rules live in [`.ai/integrations/PORACODE.md`](.ai/integrations/PORACODE.md).

### 1.1 Orchestrated Mode — Preferred

When the current harness supports delegated agents, use orchestrated mode.

The supervisor MUST:

1. read this constitution, the task, relevant subsystem `AGENTS.md`, ADRs, and architecture docs;
2. classify task complexity/risk/verification strength;
3. follow `.ai/ORCHESTRATOR.md`;
4. resolve each stage model through `.ai/MODEL_ROUTER.md`;
5. delegate specialist work through the available orchestration mechanism;
6. preserve independent review;
7. continue review -> fix -> re-review until required quality gates pass or a real blocker is reached;
8. report actual provider/model/effort used per stage.

**Do not require the user to manually copy/paste prompts between agents when delegated execution is available and working.**

### 1.2 Manual Fallback Mode

If orchestration/delegation is unavailable or the user explicitly requests manual execution, each stage must end with a structured **Next Stage Handoff** containing:

- target next stage/workflow file;
- recommended model/effort;
- availability fallback;
- alternative;
- escalation trigger;
- complete copy-paste prompt populated with task/branch/verification context.

Manual handoff is a fallback, not the default when Poracode/Crossagents is working.

### 1.3 Independent Review Invariant

The implementation agent must not be the final approval authority for its own substantive patch.

Prefer a different provider/model family for independent review when practical. Passing tests are evidence, not proof, especially for concurrency, payments, security, migrations, idempotency, and distributed state.

---

## 2. Technology Stack & Subsystem References

| Domain | Key Technologies | Specialized Rules |
|---|---|---|
| Backend | Java 21, Spring Boot 4.1.x, Spring Cloud 2025.1, PostgreSQL, Kafka, Redis, Testcontainers | `backend/AGENTS.md` |
| Frontend | Angular 22.x, Angular Material 22.x, TailwindCSS v4, TypeScript, RxJS/STOMP | `frontend/AGENTS.md` |
| Common modules | `common-domain`, `common-events`, `common-observability`, `common-security` | Section 4 |
| Architecture | System, DB, auth, messaging, REST/frontend contracts | `.ai/architecture/` |
| ADRs | Structural decisions and invariant changes | `.ai/decisions/` |
| Engineering workflows | Plan/build/fix/review/QA/refactor | `.ai/workflows/` |
| Autonomous orchestration | Stage sequencing/delegation | `.ai/ORCHESTRATOR.md` |
| AI model routing | Provider/model/effort/fallback/escalation | `.ai/MODEL_ROUTER.md` |
| Poracode execution | Crossagents/provider IDs/Fast-mode rules | `.ai/integrations/PORACODE.md` |

---

## 3. Repository Structure

```text
SeatFlow/
├── AGENTS.md
├── .ai/
│   ├── ORCHESTRATOR.md
│   ├── MODEL_ROUTER.md
│   ├── AI_MODEL_REFERENCE.md
│   ├── integrations/
│   │   └── PORACODE.md
│   ├── SeatFlow-Architecture-and-Implementation-Spec.md
│   ├── architecture/
│   ├── decisions/
│   ├── tasks/
│   │   ├── templates/TASK_TEMPLATE.md
│   │   ├── phase-00-... through phase-17-...
│   │   └── completed/
│   ├── tmp/                    # ignored local review/repair state only
│   └── workflows/
│       ├── 01-task-planning.md
│       ├── 02-task-execution.md
│       ├── 03-bug-fixing.md
│       ├── 04-testing-and-qa.md
│       ├── 05-code-review.md
│       └── 06-refactoring.md
├── backend/
│   ├── AGENTS.md
│   ├── pom.xml
│   ├── common/
│   └── services/
├── frontend/
│   ├── AGENTS.md
│   └── package.json
├── docker/
└── infra/
```

`.ai/tmp/` is local scratch state. Temporary review ledgers must never be committed.

---

## 4. Shared Common Modules — Do Not Duplicate

| Module | Provides | Critical rule |
|---|---|---|
| `common-domain` | `ApiErrorResponse`, `PagedResult`, validation/domain exceptions, `ErrorCode` | Do not create service-specific root error envelopes/exceptions when the common abstraction applies |
| `common-events` | `EventEnvelope<T>`, `DomainEvent`, headers/topics | Kafka domain messages must use `EventEnvelope<T>` |
| `common-observability` | global exception handler, correlation/trace plumbing | Never add a service-local `@RestControllerAdvice` that duplicates the shared handler |
| `common-security` | JWT role conversion, `SecurityRoles`, `UserContext` | Use shared security abstractions; use `UserContext.getCurrentUserId()` where applicable |

Inspect existing shared abstractions before creating new cross-cutting helpers.

---

## 5. Non-Negotiable Business & Architecture Invariants

Every engineer/agent must enforce these server-side invariants:

1. **Maximum 10 seats per reservation** — reject larger requests with the established domain error.
2. **15-minute hold duration** — holds expire strictly according to the authoritative backend contract.
3. **Zero double booking** — enforce with PostgreSQL constraints plus correct locking/version semantics; never rely on Redis/client state as the authority.
4. **PostgreSQL is source of truth** — Redis is cache/rate-limit/fan-out support only where architecture allows it.
5. **Transactional outbox** — never perform unsafe `DB commit -> direct Kafka publish` dual writes for domain events.
6. **Idempotency** — mandatory on reservation/payment creation flows defined by architecture.
7. **Environment configuration** — maintain `.env.example`; real `.env` files/secrets are local-only and never committed.
8. **Server-side authorization** — frontend guards are UX only; backend endpoints enforce auth/roles/ownership.
9. **Dedicated branch/worktree for task development** — do not write feature code directly on `develop` or `main`.
10. **Eureka + LoadBalancer for synchronous inter-service REST** — use the established load-balanced `RestClient.Builder` pattern and service-name URIs; do not hardcode service host/ports.

Critical-invariant changes require the stronger planning/review path from `.ai/MODEL_ROUTER.md`.

---

## 6. Git Branching & Delivery

### 6.1 Branches

- `develop` — integration/staging baseline
- `main` — production release branch
- `feat/p<XX>-<YYY>-<description>` — normal task branch from `develop`
- `fix/<issue-name>` — non-production bug fix targeting `develop`
- `hotfix/<issue-name>` — urgent production fix from `main`, merged back appropriately
- `docs/<topic>` — documentation/policy changes
- `refactor/<scope>` — behavior-preserving refactor
- `test/<scope>` — test-only work

### 6.2 Task Branch Rule

Before implementing a normal task:

```bash
git checkout -b feat/p<XX>-<YYY>-<description> develop
```

If Poracode/worktree orchestration is used, an equivalent isolated worktree/branch is acceptable.

Do not discard unrelated local changes to obtain a clean tree.

### 6.3 Quality Cycle Before Integration

```text
implementation
-> self-verification
-> independent review
-> findings ledger if needed
-> fix
-> re-review when required
-> final QA
-> task archival/finalization
-> commit/PR to develop
```

### 6.4 Conventional Commits

Examples:

- `feat(reservation): add hold expiration scheduler`
- `fix(payment): make webhook transition idempotent`
- `test(seatmap): add concurrent capacity regression coverage`
- `docs(ai): update orchestration model routing`

### 6.5 Integration Rules

- Feature/fix PRs target `develop`.
- `main` receives production releases from approved release flow.
- Never force-push without explicit user approval.
- Never merge to `develop`/`main` merely because an agent says it is done; required quality gates and user/repository intent still apply.

---

## 7. Task Scoping & Naming

### 7.1 Paths

- Phase directory: `.ai/tasks/phase-XX-<service-or-feature-name>/`
- Active task: `.ai/tasks/phase-XX-<...>/<YYY>-<task-description>.md`
- Completed task: `.ai/tasks/completed/phase-XX-<...>/<YYY>-<task-description>.md`
- Task ID: `TASK-P<XX>-<YYY>`
- Feature branch: `feat/p<XX>-<YYY>-<task-description>`

### 7.2 Numbering

- `P<XX>` is the two-digit phase.
- `<YYY>` is a three-digit counter starting at `001` per phase.
- Each phase has an independent task counter.

### 7.3 Orchestration Metadata

New/updated task files should include the risk metadata from `.ai/tasks/templates/TASK_TEMPLATE.md`:

- complexity;
- failure risk;
- verification strength;
- required review depth;
- affected critical invariants;
- preferred workflow.

Task files should **not hardcode long-lived provider/model choices**. Model selection is resolved at execution time by `.ai/MODEL_ROUTER.md`.

---

## 8. ADR Protocol

Create an ADR when the work:

1. introduces a new architecture pattern;
2. makes a material technology/library trade-off;
3. changes a global/domain invariant;
4. changes a cross-cutting shared-module contract;
5. changes meaningful persistence/messaging/security/distributed-consistency policy.

Do not create ADRs for routine CRUD, normal bug fixes preserving architecture, minor styling, or ordinary implementation choices already governed by existing standards.

Use `.ai/decisions/ADR-000-template.md` and document:

- Context
- Decision
- Alternatives Considered
- Consequences
- Implementation Notes
- affected tasks/specs

Set the ADR to an accepted state before dependent implementation treats it as authoritative.

---

## 9. Global Definition of Done

A task is **DONE** only when all applicable conditions are true:

- [ ] implementation satisfies task acceptance criteria without unapproved scope expansion;
- [ ] relevant build/typecheck/lint passes;
- [ ] required unit/slice/integration/concurrency/browser tests pass;
- [ ] DB migrations and entity/contracts align;
- [ ] new env vars are reflected in `.env.example` where required;
- [ ] architecture/ADR documentation is updated when architecture changed;
- [ ] substantive changes received independent review;
- [ ] all accepted review findings are resolved;
- [ ] P0/P1 and other required high-risk fixes were re-reviewed;
- [ ] no active `.ai/tmp/review-*.md` ledger remains;
- [ ] `.ai/workflows/04-testing-and-qa.md` returns `PASS` or valid `PASS WITH NON-BLOCKING NOTES`;
- [ ] active task file is moved to the completed folder only after the quality cycle;
- [ ] final orchestration output records actual provider/model/effort and verification evidence;
- [ ] commit/PR/merge state matches explicit user/repository intent.

Completion is a quality decision, not simply the end of an agent turn.

---

## 10. AI Model Selection & Reasoning Policy

### 10.1 Single Source of Truth

Read `.ai/MODEL_ROUTER.md` whenever selecting an AI for planning, implementation, debugging, review, refactoring, or QA.

Workflow files define **what to do**. `MODEL_ROUTER.md` defines **which model/provider/effort should do it**.

If a workflow contains legacy or conflicting model language, `MODEL_ROUTER.md` wins.

### 10.2 Muse Availability Invariant

When the router selects Muse Spark 1.3:

```text
Muse Spark 1.3 Contributor Free
-> if unavailable: Muse Spark 1.3 Contributor via OpenCode Go
-> if both unavailable/prohibited: router's task-specific Alternative
```

Do not skip the healthy Free route merely because the paid Go route exists.

A quality failure is not an availability failure; use repair/escalation rules instead of blindly switching Free -> Go.

### 10.3 Codex Fast Invariant

**Codex Fast mode is OFF by default.**

This includes Luna, Terra, and Sol. Fast may be enabled only when the user explicitly requests it for that run/stage.

Do not silently select `Luna High Fast`, `Terra ... Fast`, or another accelerated Codex route.

### 10.4 Cost-Effectiveness Invariant

Never automatically default to the strongest model/highest effort.

Preferred division of labor:

- Antigravity/Gemini — supervisor, routine planning, frontend/browser, normal QA;
- OpenCode/Muse — high-volume implementation/fixes/refactors/tests;
- Codex/Terra — substantive independent review, backend architecture, difficult diagnosis;
- Codex/Sol — critical money/security/concurrency/data-integrity judgment.

### 10.5 Java/Spring Invariant

Java/Spring alone is not sufficient reason to route deterministic implementation away from Muse Spark 1.3.

Use stronger Codex decision/review layers when hidden correctness risk requires them.

### 10.6 Alternative vs Fallback vs Escalation

- **Fallback:** preferred route unavailable/prohibited.
- **Alternative:** immediately usable peer route for quota/harness/privacy/diversity reasons.
- **Escalation:** stronger/more expensive route only after a concrete quality/risk trigger.

Do not conflate them.

### 10.7 Detailed Reference

Read `.ai/AI_MODEL_REFERENCE.md` only when:

- selection is ambiguous/disputed;
- quantitative benchmark/economics justification is requested;
- provider/model availability changed materially;
- task is unusually high-risk;
- evidence beyond the operational router is needed.

Do not waste ordinary task context on the full reference.

---

## 11. Orchestrated Stage Reporting

Every delegated stage should return enough structured state for the supervisor to continue:

```text
STAGE
TASK
BRANCH/WORKTREE
PROVIDER
MODEL
EFFORT
FAST MODE
STATUS/VERDICT
FILES TOUCHED
VERIFICATION RUN
REVIEW LEDGER
UNRESOLVED RISKS
NEXT STAGE
```

The supervisor must report any availability fallback or model substitution explicitly.

---

## 12. Safety & Destructive Operations

Without explicit user approval, never:

- `git reset --hard`;
- force-push;
- delete/stash unrelated user work merely for orchestration convenience;
- merge directly to `main` outside the release/hotfix policy;
- execute destructive production/data operations;
- commit secrets, tokens, `.env`, customer dumps, or sensitive credentials.

If safe progress requires one of these actions, stop and request approval with a concrete reason.
