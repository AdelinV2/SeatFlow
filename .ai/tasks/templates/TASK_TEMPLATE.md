# TASK-P<XX>-<YYY>: [Short Action-Oriented Title]

## 1. Task Metadata

- **Task ID:** `TASK-P<XX>-<YYY>`
- **Git Branch:** `feat/p<XX>-<YYY>-<short-description>`
- **Target Module:** `backend/services/<service-name>` OR `frontend/src/app/...`
- **Phase:** `Phase <XX> - [Phase Name]`
- **Related Specs:** `.ai/architecture/XX-[spec-name].md`
- **Related ADRs:** `.ai/decisions/ADR-XXX-[decision].md` (or `None`)
- **Status:** `READY FOR IMPLEMENTATION` <!-- DRAFT | READY FOR IMPLEMENTATION | IN PROGRESS | COMPLETED -->

### Orchestration Metadata

- **Complexity:** `<1-5>`
- **Failure Risk:** `Low | Medium | High | Critical`
- **Verification Strength:** `Strong | Partial | Weak`
- **Required Review Depth:** `Normal | Substantive | Critical`
- **Preferred Workflow:** `standard | critical | bugfix | refactor`
- **Affected Critical Invariants:** `<exact list or None>`

> Do not hardcode a long-lived provider/model into the task. At execution time the supervisor resolves provider/model/effort through `.ai/MODEL_ROUTER.md` and, when using Poracode, `.ai/integrations/PORACODE.md`.

---

## 2. Objective

Describe one precise, independently verifiable outcome.

Avoid broad phrases such as "improve", "handle properly", or "add validation" without exact expected behavior.

---

## 3. Critical Invariants & Failure Modes

### Invariants to Enforce

- [ ] Invariant 1
- [ ] Invariant 2
- [ ] Invariant 3

### Primary Failure Modes

- [ ] Failure mode 1 — expected prevention/behavior
- [ ] Failure mode 2 — expected prevention/behavior
- [ ] Failure mode 3 — expected prevention/behavior

For critical tasks include realistic money/security/concurrency/idempotency/data-integrity failure scenarios explicitly.

---

## 4. Dependencies / Prerequisites

List required existing behavior, services, migrations, endpoints, events, ADRs, or frontend contracts.

- `dependency/path-or-contract`
- `dependency/path-or-contract`

If implementation assumes a repository behavior that has not been verified, inspect it during planning rather than encoding an unsupported assumption here.

---

## 5. Exact File Inventory

List every expected file to create/modify/delete where practical.

- `[NEW]` `path/to/new-file`
- `[MODIFY]` `path/to/existing-file`
- `[DELETE]` `path/to/obsolete-file` (only when explicitly required)

Avoid unrelated cleanup outside this inventory unless implementation discovers a tiny prerequisite fix that is necessary and safe.

---

## 6. Technical Specifications & Contracts

### 6.1 Database / Flyway (If Applicable)

Define exact:

- table/column names;
- types;
- nullability/defaults;
- PK/FK/unique/check constraints;
- indexes;
- migration order/backfill/compatibility expectations.

```sql
-- exact approved DDL when applicable
```

### 6.2 DTO / API Contract (If Applicable)

```java
// exact record/interface signatures when useful
```

Document:

- HTTP method/path;
- request/response fields;
- validation;
- success/error statuses;
- shared error envelope behavior;
- auth/role/ownership rules.

### 6.3 Service / Repository Contract (If Applicable)

```java
// exact signatures and transactional expectations
```

State transaction/locking/idempotency requirements explicitly when relevant.

### 6.4 Messaging / Outbox Contract (If Applicable)

Define:

- topic/event name;
- `EventEnvelope` payload;
- publication transaction;
- consumer idempotency/order/retry expectations;
- compatibility requirements.

### 6.5 Inter-Service REST (If Applicable)

Use the established Eureka + Spring Cloud LoadBalancer pattern:

- `@Primary` plain `RestClient.Builder` where required by repository configuration;
- separately qualified `@LoadBalanced RestClient.Builder`;
- target `http://<service-name>` rather than hardcoded host/port;
- preserve correlation/resilience conventions.

### 6.6 Frontend Contract (If Applicable)

Define:

- models/interfaces;
- API/state service behavior;
- Signal/computed state transitions;
- loading/error/empty/disabled states;
- route/guard behavior;
- reconnect/reconciliation behavior;
- responsive/accessibility requirements.

---

## 7. Step-by-Step Implementation Sequence

Write an implementation order that does not require the Builder to invent architecture.

Example backend sequence:

1. Migration
2. Entity/value objects
3. Repository/query
4. DTOs/mapping
5. Service interface + implementation
6. Controller/client/messaging integration
7. Tests

Example frontend sequence:

1. Interfaces/models
2. API/state service
3. Shared primitives if genuinely reusable
4. Component/template/styles
5. Route/guard integration
6. Tests/browser verification

---

## 8. Test Requirements

For each required test, state **what behavior it proves**.

### Unit / Slice

- [ ] `<test>` proves `<behavior>`

### Integration / Testcontainers

- [ ] `<test>` proves `<real DB/messaging/contract behavior>`

### Concurrency / Failure

- [ ] `<test>` proves `<race/idempotency/retry invariant>`

### Frontend / Browser

- [ ] `<test/check>` proves `<state/interaction/reconnect/responsive behavior>`

Do not accept mocks as proof for properties that depend on PostgreSQL/Kafka/Stripe/browser/WebSocket semantics.

---

## 9. Verification Commands

Run the narrowest deterministic command first:

```bash
<task-specific-command>
```

Then, where required:

```bash
<module/service test command>
<build/typecheck/lint command>
<integration/concurrency/browser command>
```

Expected observable result:

- `<what passing means>`

---

## 10. Independent Review Focus

The reviewer must inspect especially:

- `<contract/invariant/file/state transition>`
- `<concurrency/security/migration/integration risk>`
- `<test oracle or compatibility risk>`

For substantive changes follow `.ai/workflows/05-code-review.md` and create `.ai/tmp/review-<task-id>.md` when actionable findings exist.

---

## 11. Acceptance Criteria

- [ ] Objective is implemented exactly.
- [ ] Critical invariants are preserved/enforced.
- [ ] Required negative/boundary/failure behavior is correct.
- [ ] Required tests/verification pass.
- [ ] API/event/DB/frontend contracts remain aligned.
- [ ] Independent review requirements are satisfied.
- [ ] Accepted findings are resolved and re-reviewed where required.
- [ ] Final QA passes.
- [ ] No active `.ai/tmp/review-*.md` ledger remains.
- [ ] Task file moves to `.ai/tasks/completed/...` only after the complete quality cycle.

---

## 12. Execution Entry Point

When orchestrated execution is available, the user/supervisor should invoke this task through `.ai/ORCHESTRATOR.md` rather than manually choosing each next agent.

Example:

```text
Implement TASK-P<XX>-<YYY> using the SeatFlow autonomous orchestration workflow.
```

The supervisor resolves every stage route dynamically from `.ai/MODEL_ROUTER.md`.
