# Workflow 01: Task Planning & Breakdown Protocol

**Role:** System Architect & Planner

---

## 1. Goal & Responsibilities

The Architect/Planner role transforms high-level architectural requirements into **atomic, unambiguous, deterministic task files** that implementation agents can execute without making architectural guesses.

---

## 2. Planning Principles (Zero-Ambiguity Rule)

1. **One Task = One Atomic Feature Slice:** A task should touch one microservice (or frontend domain) and be completable in a single pass (typically 4-10 files).
2. **Explicit Contracts Over General Descriptions:** Never say "create an endpoint to book seats". Specify the exact HTTP method, path, `@RequestBody` record fields with validation annotations, response record, and status codes.
3. **Pre-designed Schemas:** Always provide the exact Flyway SQL statements including primary keys (`UUID DEFAULT gen_random_uuid()`), nullability, foreign keys, and indexes.
4. **Enforce Invariants in the Task:** Explicitly list domain invariants (e.g., 15-minute expiration, 10 seats max) in Section 2 of the task file.
5. **Deterministic Verification Command:** Every task must specify the exact test command (e.g., `mvn test -Dtest=ReservationServiceTest`) so the implementation agent can self-verify.

---

## 3. Planning Step-by-Step Sequence

```
1. Read relevant architecture docs in .ai/architecture/ (e.g. 02-microservices-spec.md, 03-database-models.md, 07-frontend-specification.md).
2. Check existing code and common modules (backend/common/).
3. If an architectural decision is new or deviates from spec, create an ADR in .ai/decisions/.
4. Copy .ai/tasks/templates/TASK_TEMPLATE.md into the appropriate phase folder:
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
5. Fill out all sections:
   - Metadata (Service, Phase, Related Docs)
   - Invariants
   - File Inventory ([NEW], [MODIFY], [DELETE])
   - SQL Schemas & DTO Record definitions
   - Method signatures
   - Step-by-step implementation sequence
   - Verification commands
6. Set Status to READY FOR IMPLEMENTATION.
```
