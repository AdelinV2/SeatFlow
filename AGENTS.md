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
│ • Receives single task: .ai/tasks/phase-X/XXX-task.md       │
│ • Reads stack rules in backend/AGENTS.md or frontend/AGENTS.md│
│ • Strictly executes the implementation sequence             │
│ • Writes production code + comprehensive unit/slice tests   │
│ • Follows .ai/workflows/02-task-execution.md                │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. FIXER / DEBUGGER                                         │
│ • Analyzes test logs or compiler outputs                    │
│ • Follows .ai/workflows/03-bug-fixing.md                   │
│ • Writes a regression-reproducing test first, then fixes    │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. QA / TEST ENGINEER                                       │
│ • Follows .ai/workflows/04-testing-and-qa.md                │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Technology Stack & Subsystem References

| Domain | Key Technologies | Specialized Rules File |
|---|---|---|
| **Backend** | Java 21 (LTS), Spring Boot 4.1.x, Spring Cloud 2025.1 (Oakwood), PostgreSQL, Kafka, Redis, Testcontainers | [backend/AGENTS.md](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/backend/AGENTS.md) |
| **Frontend** | Angular 22.x, Angular Material 22.x, TailwindCSS v4, TypeScript 5.x, @stomp/stompjs | [frontend/AGENTS.md](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/frontend/AGENTS.md) |
| **Common Modules** | `common-domain`, `common-events`, `common-observability`, `common-security` | Section 4 of this document |
| **Workflows** | Task planning, implementation sequence, bug fixing, QA | [.ai/workflows/](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/.ai/workflows/) |
| **Architecture** | System overview, database models, auth, messaging, REST contracts | [.ai/architecture/](file:///c:/Users/adeli/OneDrive/Projects/SeatFlow/.ai/architecture/) |

---

## 3. Repository Structure

```
SeatFlow/
├── AGENTS.md                          # This file (Global Constitution)
│
├── .ai/
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
│   │   └── completed/                 # Archived completed tasks (organized by phase)
│   │       ├── phase-00-foundation/
│   │       └── ...
│   └── workflows/                     # Step-by-step engineering protocols
│       ├── 01-task-planning.md
│       ├── 02-task-execution.md
│       ├── 03-bug-fixing.md
│       └── 04-testing-and-qa.md
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
9. **Mandatory Dedicated Branch per Task:** Never write code or modify files directly on `develop` or `main`. Every implementation task MUST start by checking out its dedicated feature branch (`feat/<task-id>-<desc>` from `develop`).

---

## 6. Git Branching Strategy & Multi-Cloud CI/CD

### 6.1 Branch Naming Conventions & Architecture
SeatFlow follows a clean 3-tier **Environment Branching Strategy** designed for multi-cloud deployment:

```text
┌─────────────────────────────────────────────────────────────┐
│ 1. FEATURE / FIX BRANCHES (Local Task Development)          │
│ • feat/<task-id>-<description> (branched from develop)      │
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
- **`feat/<phase-or-task-id>-<description>`** — Feature branches created from `develop` (e.g. `feat/phase-00-foundation`, `feat/001-common-domain`).
- **`fix/<issue-name>`** — Bug fixes targeting `develop`.
- **`hotfix/<issue-name>`** — Critical production fixes branched directly from `main` and merged back into both `main` and `develop`.
- **`docs/<topic>`** — Documentation, architecture, ADRs.
- **`refactor/<scope>`** — Code refactoring without behavioral changes.
- **`test/<scope>`** — Adding or modifying test suites.

### 6.2 GitHub Workflow & Environments
1. **Mandatory Automatic Branch Checkout:** Before creating or modifying any code/files for a task, create a dedicated branch from `develop`:
   ```bash
   git checkout -b feat/<task-id>-<description> develop
   ```
2. **Local Environment:** Copy `.env.example` to `.env` in the target service directory if not already created.
3. **Execute & Test:** Follow the task specification and pass local unit/slice tests.
4. **Commit with Conventional Commits:**
   - `feat(<scope>): add reservation hold scheduler`
   - `fix(<scope>): resolve optimistic locking retry in payment processing`
   - `test(<scope>): add concurrency test for seat hold race condition`
   - `docs(<scope>): update ADR-002 with outbox polling benchmarks`
5. **Pull Request to `develop`:** Open PR targeting `develop`. GitHub Actions runs `ci-pr-check.yml`.
6. **Staging Deploy:** Merging into `develop` triggers `cd-staging.yml` (GCP Staging + Azure Entra Sandbox).
7. **Production Release:** Open release PR from `develop` into `main`. Merging triggers `cd-production.yml` (GCP Production + Azure Entra Prod).

---

## 7. Global Definition of Done (DoD)

A task is considered **DONE** only when:
- [ ] Code strictly satisfies the task specification without extra unrequested features.
- [ ] Compiles cleanly with zero compiler warnings or lint errors.
- [ ] All unit and integration tests pass locally.
- [ ] Local `.env.example` updated if new environment variables were introduced.
- [ ] Database migrations are backwards-compatible and indexed.
- [ ] Relevant documentation/ADR updated if an architectural decision was modified.
- [ ] Task file moved from active phase to `.ai/tasks/completed/<phase-name>/`.

