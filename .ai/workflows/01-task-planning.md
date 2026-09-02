# Workflow 01: Task Planning & Breakdown Protocol

**Role:** System Architect & Planner

---

## 1. Goal & Responsibilities

The Architect/Planner role transforms high-level architectural requirements into **atomic, unambiguous, deterministic task files** that implementation agents can execute without making architectural guesses.

---

## 2. Planning Principles & Task Naming Standards

### 2.1 Planning Rules (Zero-Ambiguity Rule)
1. **One Task = One Atomic Feature Slice:** A task should touch one microservice (or frontend domain) and be completable in a single pass (typically 4-10 files).
2. **Explicit Contracts Over General Descriptions:** Never say "create an endpoint to book seats". Specify the exact HTTP method, path, `@RequestBody` record fields with validation annotations, response record, and status codes.
3. **Pre-designed Schemas:** Always provide the exact Flyway SQL statements including primary keys (`UUID DEFAULT gen_random_uuid()`), nullability, foreign keys, and indexes.
4. **Enforce Invariants in the Task:** Explicitly list domain invariants (e.g., 15-minute expiration, 10 seats max) in Section 2 of the task file.
5. **Deterministic Verification Command:** Every task must specify the exact test command (e.g., `mvn test -Dtest=ReservationServiceTest`) so the implementation agent can self-verify.

### 2.2 Task Scoping & Naming Conventions
- **Task ID Format:** `TASK-P<XX>-<YYY>` (e.g., `TASK-P01-001`, `TASK-P01-002`, `TASK-P02-001`).
- **Task File Name:** `.ai/tasks/phase-XX-<phase-name>/<YYY>-<task-description>.md` (e.g., `.ai/tasks/phase-01-user-service/001-user-entity-and-repository.md`).
- **Completed Path:** `.ai/tasks/completed/phase-XX-<phase-name>/<YYY>-<task-description>.md`.
- **Git Branch Format:** `feat/p<XX>-<YYY>-<task-description>` (e.g., `feat/p01-001-user-entity`).
- **Per-Phase Scoping:** Task numbers always start at `001` within each phase. Phase 2 starts at `TASK-P02-001` regardless of how many tasks Phase 1 contains.

---

## 3. Architecture Decision Records (ADRs) Protocol

### 3.1 When to Generate an ADR
The Architect/Planner MUST create an ADR in `.ai/decisions/` prior to task breakdown if the feature involves:
1. **Introducing a New Architectural Pattern:** (e.g., Transactional Outbox, CQRS, Event Sourcing, Saga orchestration).
2. **Choosing Between Competing Technologies/Libraries:** (e.g., STOMP over WebSocket vs SSE, ZXing vs external QR service, Redis vs Postgres locking).
3. **Modifying System-Wide Invariants or Policies:** (e.g., adjusting hold TTL, seat reservation limits, auth token expiration).
4. **Changes to Shared Common Libraries:** (e.g., `common-security`, `common-domain`, `common-events`, `common-observability`).
5. **Non-Trivial Trade-Offs:** Any decision where positive benefits come with architectural constraints or trade-offs that future engineers must know.

### 3.2 When NOT to Generate an ADR
- Standard CRUD development following established patterns in `.ai/architecture/`.
- Routine bug fixes or minor code refactorings.
- Routine UI layout, CSS, or straightforward component implementations.

### 3.3 How to Generate an ADR
1. Copy `.ai/decisions/ADR-000-template.md` to `.ai/decisions/ADR-XXX-<short-title>.md` (e.g., `ADR-001-transactional-outbox.md`).
2. Fill in:
   - **Context:** The specific architectural challenge and requirements.
   - **Decision:** The chosen approach and technical rationale.
   - **Alternatives Considered:** Options analyzed and why they were rejected.
   - **Consequences:** Positive benefits and negative trade-offs/mitigations.
   - **Implementation Notes:** Modules affected and links to tasks.
3. Set status to `ACCEPTED` once aligned.
4. Reference the ADR in the corresponding task file(s) under `Related ADRs:`.

---

## 4. Planning Step-by-Step Sequence

```
1. Read relevant architecture docs in .ai/architecture/ (e.g. 02-microservices-spec.md, 03-database-models.md, 07-frontend-specification.md).
2. Check existing code and common modules (backend/common/).
3. Evaluate if an ADR is required (Section 3). If yes, write and commit the ADR in .ai/decisions/.
4. Determine the Phase folder and the next sequential 3-digit task number for that phase (e.g. phase-01-user-service/001-...).
5. Copy .ai/tasks/templates/TASK_TEMPLATE.md into .ai/tasks/phase-XX-<name>/<YYY>-<task-desc>.md:
   - .ai/tasks/phase-00-foundation/
   - .ai/tasks/phase-01-user-service/
   - .ai/tasks/phase-02-seat-map-service/
   - .ai/tasks/phase-03-event-service/
   - .ai/tasks/phase-04-reservation-service/
   - .ai/tasks/phase-05-payment-service/
   - .ai/tasks/phase-06-ticket-service/
   - .ai/tasks/phase-07-realtime-service/
   - .ai/tasks/phase-08-notification-service/
   - .ai/tasks/phase-09-frontend-portal/
   - .ai/tasks/phase-10-devops-observability/
   - .ai/tasks/phase-11-advanced-seat-map-designer/
   - .ai/tasks/phase-12-event-sessions/
   - .ai/tasks/phase-13-refunds-ticket-cancellation/
   - .ai/tasks/phase-14-admin-analytics/
   - .ai/tasks/phase-15-ai-assistant-mcp/
   - .ai/tasks/phase-16-public-site-legal-support/
   - .ai/tasks/phase-17-testing-quality-final-polish/
6. Fill out all sections:
   - Header with TASK-P<XX>-<YYY>
   - Metadata (Target Module, Phase, Related Docs, Related ADRs)
   - Objective & Critical Invariants
   - Exact File Inventory ([NEW], [MODIFY], [DELETE])
   - SQL Schemas & DTO Record definitions
   - Service Interface Contracts
   - Step-by-step implementation sequence
   - Deterministic verification command
7. Set Status to READY FOR IMPLEMENTATION.
```
