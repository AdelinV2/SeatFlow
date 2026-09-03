# SeatFlow — Global Engineering & Architecture Constitution

This document is the **Global Engineering Constitution** for the SeatFlow project. It defines the operating model, non-negotiable architectural invariants, automated implementation workflows, and cross-cutting standards.

The authoritative technical blueprint lives in `.ai/SeatFlow-Architecture-and-Implementation-Spec.md` and the modular documentation in `.ai/architecture/`.

---

## 1. Automated Engineering Operating Model

SeatFlow is engineered through an autonomous, modular engineering pipeline:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. ARCHITECT & PLANNER                                      │
│ • Ingests .ai/architecture/ and ADRs                        │
│ • Breaks down features into atomic tasks in .ai/tasks/      │
│ • Uses .ai/tasks/templates/TASK_TEMPLATE.md                 │
│ • Follows .ai/workflows/01-task-planning.md                 │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. BUILDER / IMPLEMENTER                                    │
│ • Receives one approved atomic task                         │
│ • Reads backend/AGENTS.md or frontend/AGENTS.md             │
│ • Implements the smallest correct diff + tests              │
│ • Self-verifies before independent review                   │
│ • Follows .ai/workflows/02-task-execution.md                │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. INDEPENDENT CODE REVIEWER                                │
│ • Reviews diff + surrounding contracts proactively          │
│ • Searches for bugs, security/invariant issues & test gaps  │
│ • Uses temporary .ai/tmp/review-*.md ledger when needed     │
│ • Follows .ai/workflows/05-code-review.md                   │
└──────────────────────────────┬──────────────────────────────┘
                               │ findings
                     ┌─────────┴─────────┐
                     │                   │
                     ▼                   │ no findings
┌─────────────────────────────────────────────────────────────┐
│ 4. FIXER / DEBUGGER                                         │
│ • Reproduces/validates findings and fixes root causes       │
│ • Adds regression coverage where meaningful                │
│ • Re-runs verification and triggers re-review when required │
│ • Follows .ai/workflows/03-bug-fixing.md                   │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               └──────────────► re-review

                     after review/fix cycle
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. QA / FINAL QUALITY GATE                                  │
│ • Validates tests, contracts, diff hygiene and cleanup      │
│ • Rejects unresolved/active temporary review ledgers        │
│ • Follows .ai/workflows/04-testing-and-qa.md                │
└─────────────────────────────────────────────────────────────┘
```

For structural changes that promise behavior preservation, use `.ai/workflows/06-refactoring.md` during implementation, then run the same independent review and final QA gates.

---

## 2. Technology Stack & Subsystem References

| Domain | Key Technologies | Specialized Rules File |
|---|---|---|
| **Backend** | Java 21 (LTS), Spring Boot 4.1.x, Spring Cloud 2025.1 (Oakwood), PostgreSQL, Kafka, Redis, Testcontainers | [backend/AGENTS.md](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/backend/AGENTS.md) |
| **Frontend** | Angular 22.x, Angular Material 22.x, TailwindCSS v4, TypeScript 5.x, @stomp/stompjs | [frontend/AGENTS.md](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/frontend/AGENTS.md) |
| **Common Modules** | `common-domain`, `common-events`, `common-observability`, `common-security` | Section 4 of this document |
| **Workflows** | Planning, implementation, debugging, QA, proactive code review, refactoring | [.ai/workflows/](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/.ai/workflows/) |
| **Architecture** | System overview, database models, auth, messaging, REST contracts | [.ai/architecture/](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/.ai/architecture/) |
| **AI Model Routing** | Cost-effective model & reasoning effort selection | [.ai/MODEL_ROUTER.md](.ai/MODEL_ROUTER.md) (Reference: [.ai/AI_MODEL_REFERENCE.md](.ai/AI_MODEL_REFERENCE.md)) |

---

## 3. Repository Structure

```
SeatFlow/
├── AGENTS.md                          # This file (Global Constitution)
│
├── .ai/
│   ├── MODEL_ROUTER.md                # AI model & reasoning effort routing policy
│   ├── AI_MODEL_REFERENCE.md          # Detailed benchmark & economics reference (on-demand only)
│   ├── architecture/                  # Modular architecture specifications
│   ├── decisions/                     # Architecture Decision Records (ADRs)
│   ├── tasks/                         # Phased execution tasks
│   │   ├── templates/TASK_TEMPLATE.md # Standardized task format
│   │   ├── phase-00-foundation/       # Common modules, Eureka, Gateway
│   │   ├── phase-01-user-service/     # Identity & User service
│   │   ├── phase-02-seat-map-service/ # Venue & Seat Map service
│   │   ├── phase-03-event-service/    # Event catalog service
│   │   ├── phase-04-reservation-service/ # Reservation & hold service
│   │   ├── phase-05-payment-service/  # Payment & Stripe service
│   │   ├── phase-06-ticket-service/   # Ticket & QR code service
│   │   ├── phase-07-realtime-service/ # Realtime WebSocket service
│   │   ├── phase-08-notification-service/ # Notification & email service
│   │   ├── phase-09-frontend-portal/  # Angular client & admin UI
│   │   ├── phase-10-devops-observability/ # Docker, monitoring & GCP deployment
│   │   ├── phase-11-advanced-seat-map-designer/ # 2D visual layout editor
│   │   ├── phase-12-event-sessions/   # Multiple scheduled showings per event
│   │   ├── phase-13-refunds-ticket-cancellation/ # 24h cutoff & Stripe refund workflow
│   │   ├── phase-14-admin-analytics/  # Event-driven CQRS analytics service
│   │   ├── phase-15-ai-assistant-mcp/ # Spring AI & controlled domain tool calling
│   │   ├── phase-16-public-site-legal-support/ # Public routes, disclosures & support
│   │   ├── phase-17-testing-quality-final-polish/ # E2E Playwright & system-wide verification
│   │   └── completed/                 # Archived completed tasks (organized by phase)
│   │       ├── phase-00-foundation/
│   │       └── ...
│   └── workflows/                     # Step-by-step engineering protocols
│       ├── 01-task-planning.md
│       ├── 02-task-execution.md
│       ├── 03-bug-fixing.md
│       ├── 04-testing-and-qa.md
│       ├── 05-code-review.md
│       └── 06-refactoring.md
│
├── backend/                           # Java 21 / Spring Boot 4.1.x Microservices
│   ├── AGENTS.md                      # Backend engineering standards
│   ├── pom.xml                        # Root parent POM
│   ├── common/                        # Shared libraries (common-domain, etc.)
│   └── services/                      # Microservice modules
│
└── frontend/                          # Angular 22 / TailwindCSS v4 Web Application
    ├── AGENTS.md                      # Frontend engineering standards
    └── package.json
```

`.ai/tmp/` is reserved for ignored local review/repair state and must not contain committed artifacts.

---

## 4. Shared Common Modules (DO NOT Duplicate)

The shared modules located in `backend/common/` are dependencies of all microservices:

| Module | Provided Abstractions | Critical Rule |
|---|---|---|
| **`common-domain`** | `ApiErrorResponse`, `PagedResult`, `ValidationError`, base exceptions (`BusinessException`, `ConflictException`, `ResourceNotFoundException`, `ValidationException`), `ErrorCode` enum. | Never create custom error response DTOs or custom root exceptions in business services. |
| **`common-events`** | `EventEnvelope<T>`, `DomainEvent` contract, `EventHeaders`, `EventTopics`. | All Kafka messages must be wrapped in `EventEnvelope<T>`. |
| **`common-observability`** | Auto-configured `GlobalExceptionHandler` (`@RestControllerAdvice`), `CorrelationContext`, `CorrelationIdFilter`, trace context propagation. | **Never create a `@RestControllerAdvice` in any microservice.** |
| **`common-security`** | Auto-configured JWT role converter (`JwtRoleConverter`), `SecurityRoles`, `UserContext` helper. | Use `UserContext.getCurrentUserId()` for user extraction. |

---

## 5. Non-Negotiable Business & Architectural Invariants

Every engineer and agent must enforce these server-side invariants:

1. **Maximum 10 Seats per Reservation:** Reject with `RESERVATION_LIMIT_EXCEEDED` if more than 10 seats are requested.
2. **15-Minute Seat Hold Duration:** Seat holds expire strictly after 15 minutes. Enforced in PostgreSQL and swept by a background scheduler.
3. **Zero Double-Booking Guarantee:** Prevent concurrent bookings using PostgreSQL unique constraints + pessimistic/optimistic locking.
4. **PostgreSQL is Source of Truth:** Redis is a temporary cache/lock store. PostgreSQL state is always authoritative.
5. **Transactional Outbox Pattern:** No raw `db commit → Kafka publish` dual writes. All domain events must be committed to `outbox_events` in the same transaction.
6. **Idempotency Keys:** Mandatory on `POST /api/reservations` and `POST /api/payments`.
7. **Environment Variable Configuration (`.env`):** Every microservice and the frontend must maintain a `.env.example` template with dummy defaults. Real `.env` files are local-only, strictly `.gitignore`d, and never committed. In Staging/Production, variables are injected via GCP Secret Manager and GitHub Environments.
8. **Server-Side Authorization:** Never rely solely on frontend route guards. All endpoints must validate JWT roles server-side.
9. **Mandatory Dedicated Branch per Task:** Never write code or modify files directly on `develop` or `main`. Every implementation task MUST start by checking out its dedicated feature branch (`feat/p<XX>-<YYY>-<desc>` from `develop`).
10. **Synchronous Inter-Service Communication via Eureka & LoadBalancer:** All synchronous REST calls between microservices MUST resolve target instances dynamically using Eureka Service Discovery and Spring Cloud LoadBalancer (`@LoadBalanced RestClient.Builder` with target service URI `http://<service-name>`, e.g., `http://event-service`). Never hardcode hostnames or port numbers. Every service declaring load-balanced builders must also declare a `@Primary` plain `RestClient.Builder` bean to preserve Eureka client's internal registration mechanism.

---

## 6. Git Branching Strategy & Multi-Cloud CI/CD

### 6.1 Branch Naming Conventions & Architecture
SeatFlow follows a clean 3-tier **Environment Branching Strategy** designed for multi-cloud deployment:

```text
┌─────────────────────────────────────────────────────────────┐
│ 1. FEATURE / FIX BRANCHES (Local Task Development)          │
│ • feat/p<XX>-<YYY>-<desc> (branched from develop)           │
│ • fix/<issue-name>                                          │
│ • docs/<topic>, refactor/<scope>, test/<scope>              │
└──────────────────────────────┬──────────────────────────────┘
                               │ Pull Request (passes ci-pr-check.yml)
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. DEVELOP BRANCH (Integration & Local Dev Baseline)        │
│ • Integration branch for all completed features             │
│ • Represents latest green local development state           │
│ • Auto-deploys to GCP Staging + Azure Entra Sandbox         │
└──────────────────────────────┬──────────────────────────────┘
                               │ Release PR (vX.Y.Z) / Manual Approval
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. MAIN BRANCH (Production Cloud Deployment)                │
│ • Protected branch: 100% production-ready, zero direct push │
│ • Auto-deploys to GCP Production + Azure Entra Prod CIAM    │
│ • hotfix/<issue-name> branched from main for urgent fixes   │
└─────────────────────────────────────────────────────────────┘
```

- **`develop`** — Default integration and Staging branch. All feature PRs target `develop`. Auto-deploys to GCP Staging.
- **`main`** — Protected Production branch. Deploys to GCP Production only via approved release PRs from `develop` or release tags (`v*.*.*`).
- **`feat/p<XX>-<YYY>-<description>`** — Feature branches created from `develop` with phase and task index (e.g. `feat/p01-001-user-entity`, `feat/p02-001-venue-models`).
- **`fix/<issue-name>`** — Bug fixes targeting `develop`.
- **`hotfix/<issue-name>`** — Critical production fixes branched directly from `main` and merged back into both `main` and `develop`.
- **`docs/<topic>`** — Documentation, architecture, ADRs.
- **`refactor/<scope>`** — Code refactoring without behavioral changes.
- **`test/<scope>`** — Adding or modifying test suites.

### 6.2 GitHub Workflow & Environments
1. **Mandatory Automatic Branch Checkout:** Before creating or modifying any code/files for a task, create a dedicated branch from `develop`:
   ```bash
   git checkout -b feat/p<XX>-<YYY>-<description> develop
   ```
2. **Local Environment:** Copy `.env.example` to `.env` in the target service directory if not already created.
3. **Execute & Self-Verify:** Follow the task specification, write required tests, run deterministic verification, and inspect the diff.
4. **Independent Quality Cycle:** Run `.ai/workflows/05-code-review.md`; resolve accepted findings through `.ai/workflows/03-bug-fixing.md`; re-review when required; then pass `.ai/workflows/04-testing-and-qa.md`. Temporary `.ai/tmp/review-*.md` ledgers must be deleted only after resolution and must never be committed.
5. **Commit with Conventional Commits:**
   - `feat(<scope>): add reservation hold scheduler`
   - `fix(<scope>): resolve optimistic locking retry in payment processing`
   - `test(<scope>): add concurrency test for seat hold race condition`
   - `docs(<scope>): update ADR-002 with outbox polling benchmarks`
6. **Pull Request to `develop`:** Open PR targeting `develop`. GitHub Actions runs `ci-pr-check.yml`.
7. **Staging Deploy:** Merging into `develop` triggers `cd-staging.yml` (GCP Staging + Azure Entra Sandbox).
8. **Production Release:** Open release PR from `develop` into `main`. Merging triggers `cd-production.yml` (GCP Production + Azure Entra Prod).

---

## 7. Task Scoping & Naming Conventions

All implementation tasks follow a standardized **phase-scoped hierarchy** to ensure zero naming collisions and infinite scalability across project phases:

### 7.1 Hierarchy & Paths
- **Phase Task Directory:** `.ai/tasks/phase-XX-<service-or-feature-name>/`
- **Active Task File:** `.ai/tasks/phase-XX-<service-or-feature-name>/<YYY>-<task-description>.md`
- **Completed Task File:** `.ai/tasks/completed/phase-XX-<service-or-feature-name>/<YYY>-<task-description>.md`
- **Task Header & ID:** `# TASK-P<XX>-<YYY>: [Short Action-Oriented Title]`
- **Git Feature Branch:** `feat/p<XX>-<YYY>-<task-description>`

### 7.2 Numbering Rules
1. **Phase Prefix (`P<XX>`):** Represents the 2-digit phase number (`P00`, `P01`, `P02`, ..., `P17`).
2. **Task Number (`<YYY>`):** 3-digit sequence starting at `001` per phase (`001`, `002`, `003`...).
3. **Phase Independence:** Each phase has its own task counter starting at `001`. Adding tasks to Phase 1 does not shift or impact task IDs in Phase 2.

---

## 8. Architecture Decision Records (ADRs) Protocol

SeatFlow uses **Architecture Decision Records (ADRs)** located in `.ai/decisions/` to maintain the authoritative, immutable history of structural engineering choices.

### 8.1 When an ADR is MANDATORY
An Architect or Agent MUST create an ADR when:
1. **Introducing a New Architectural Pattern:** (e.g., Transactional Outbox pattern, CQRS read model, Saga orchestration).
2. **Technology / Library Selection:** Choosing between competing tools or libraries when multiple viable options exist (e.g., STOMP over WebSockets vs SSE, ZXing QR generation, Redis vs in-memory caching).
3. **Modifying Domain Invariants or Policies:** Changes to critical business rules (e.g., altering the 15-minute seat hold timeout, changing the 10-seats-per-reservation limit, modifying concurrency locking models).
4. **Cross-Cutting Shared Module Contracts:** Adding or modifying shared infrastructure abstractions in `backend/common/` (e.g., `common-security` authentication flow, `common-domain` error envelope contracts).
5. **Data Storage & Consistency Trade-Offs:** Introducing distributed locks, database indexing strategies under high write contention, or schema migration patterns.

### 8.2 When an ADR is NOT Required
- Standard CRUD implementations following already documented service patterns.
- Regular bug fixes and patches that do not alter the system topology or invariant rules.
- Minor UI component styling or non-structural frontend refactoring.

### 8.3 ADR Creation & Lifecycle Workflow
1. **Template:** Copy [.ai/decisions/ADR-000-template.md](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/.ai/decisions/ADR-000-template.md) to `.ai/decisions/ADR-XXX-<short-title>.md` using a sequential 3-digit number.
2. **Required Sections:** Complete Context, Decision, Alternatives Considered (with explicit pros/cons), Consequences (positive and negative trade-offs), and Implementation Notes.
3. **Status Flow:** `PROPOSED` → `ACCEPTED` (upon implementation approval) → `DEPRECATED` | `SUPERSEDED by ADR-YYY`.
4. **Task Linking:** Link the created ADR in Section 1 of the corresponding Task file (`Related ADRs:`).

---

## 9. Global Definition of Done (DoD)

A task is considered **DONE** only when:
- [ ] Code strictly satisfies the task specification without extra unrequested features.
- [ ] Compiles cleanly and relevant lint/type checks pass.
- [ ] Required unit, integration, concurrency, contract, and/or frontend tests pass for the changed risk surface.
- [ ] Local `.env.example` is updated if new environment variables were introduced.
- [ ] Database migrations are compatible, correctly constrained/indexed, and validated for the task's rollout assumptions.
- [ ] Relevant documentation/ADR is updated if an architectural decision was modified.
- [ ] Independent review using `.ai/workflows/05-code-review.md` is complete for substantive changes.
- [ ] All accepted review findings are resolved; P0/P1 and other high-risk fixes are re-reviewed as required.
- [ ] No active `.ai/tmp/review-*.md` ledger remains and no `.ai/tmp/` artifact is committed.
- [ ] `.ai/workflows/04-testing-and-qa.md` final quality gate returns PASS or an explicitly valid PASS WITH NON-BLOCKING NOTES.
- [ ] Task file is moved from the active phase to `.ai/tasks/completed/<phase-name>/` only after the quality cycle completes.

---

## 10. AI Model Selection & Reasoning Effort Routing

Whenever asked for recommendations on which AI model, persona, or reasoning effort to use for a development task (e.g., *"Which AI should I use for this task?"*, *"Which model should implement TASK-P01-002?"*, *"What model and reasoning effort should I use?"*, *"Should I use Luna, Terra, Sol, or Gemini for this?"*, *"Which AI should do the planning/review/debugging/implementation?"*):

1. **Primary Operational Policy:** Read [`.ai/MODEL_ROUTER.md`](.ai/MODEL_ROUTER.md) to classify the task (complexity, failure risk, context size, agentic demand, verification strength) and determine the recommended model and reasoning effort.
2. **Standard Recommendation Format:** Follow the current template in `.ai/MODEL_ROUTER.md`:
   - **Recommended:** `MODEL` — `EFFORT`
   - **Why:** 2–4 sentences tied to the actual failure mode, agentic demand, and verification strength.
   - **Alternative:** `MODEL` — `EFFORT`.
   - **Trade-off:** one concise sentence explaining what the alternative gains/loses relative to the recommendation.
   - **Task profile:** Complexity, Risk, Context, Agentic demand, Verification.
   - **Implementation model:** only if different from the reasoning/review model.
   - **Review model:** only when a separate quality gate is needed.
   - **Escalate to:** stronger model only under a concrete trigger condition.
3. **Alternative vs Escalation Invariant:** An **Alternative** is an immediately usable peer route for quota/provider/privacy/harness/diversity reasons. An **Escalation** is a stronger or more expensive route justified by failure, ambiguity, weak verification, or increased risk. Never conflate them.
4. **Java/Spring Routing Invariant:** Java or Spring alone is **not** sufficient justification to route deterministic implementation away from Muse Spark 1.3. Follow `.ai/MODEL_ROUTER.md` risk and verification rules instead.
5. **Cost-Effectiveness Invariant:** Never automatically default to the strongest model or highest reasoning effort. Choose the most cost-effective configuration that is sufficiently reliable for the task.
6. **Workflow Invariant:** Model choice does not replace process. Planning, execution, debugging, QA, code review, and refactoring must follow their corresponding `.ai/workflows/*.md` protocol.
7. **Detailed Reference (On-Demand Only):** Read [`.ai/AI_MODEL_REFERENCE.md`](.ai/AI_MODEL_REFERENCE.md) **only** when additional evidence is genuinely required:
   - The model choice is ambiguous or disputed;
   - The user explicitly requests a detailed comparison between models;
   - The user asks why one model is better than another for a specific workload;
   - Quantitative cost/performance or benchmark justification is requested;
   - The task is unusually complex, high-risk, or mission-critical.

   > **Context Conservation Rule:** **Do NOT** load [`.ai/AI_MODEL_REFERENCE.md`](.ai/AI_MODEL_REFERENCE.md) for ordinary model-selection requests to avoid wasting context tokens.
