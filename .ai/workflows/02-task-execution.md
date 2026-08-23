# Workflow 02: Task Execution Protocol

**Role:** Builder / Implementer

---

## 1. Goal & Rules of Engagement

The Builder/Implementer role receives a single task specification (`.ai/tasks/phase-X/XXX-task.md`) and implements it with zero deviation.

### Non-Negotiable Rules:
1. **Do not improvise architecture:** Follow the exact schema, method signatures, and directory layout given in the task file.
2. **Follow the stack standards:**
   - For backend tasks: Follow [backend/AGENTS.md](../../backend/AGENTS.md).
   - For frontend tasks: Follow [frontend/AGENTS.md](../../frontend/AGENTS.md).
3. **Strict creation order:**
   - Backend: Flyway Migration → Entity/Enum → Repository → Request/Response DTO Records → MapStruct Mapper → Service Interface → Service Impl → Controller → Tests.
   - Frontend: Interfaces/Models → Service → Component → Template → Route → Tests.
4. **No `@Data` on JPA Entities:** Always use explicit `@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, `@AllArgsConstructor`.
5. **No custom GlobalExceptionHandler:** Use `common-observability` and `common-domain`.

---

## 2. Step-by-Step Execution Sequence

```
1. Checkout a dedicated feature branch from main:
   git checkout -b feat/<task-id>-<description>
2. Ingest the assigned task file from .ai/tasks/phase-X/XXX-task.md.
3. Verify dependent classes in backend/common/ (e.g. ApiErrorResponse, EventEnvelope).
4. Write files one by one in the order specified in Section 5 of the task.
5. Add comprehensive unit tests and integration tests covering:
   - Happy path
   - Business invariant violations (e.g. limit exceeded)
   - Edge cases (null inputs, optimistic locking conflicts)
6. Run the Verification Command provided in the task file:
   - Backend: mvn clean test -Dtest=...
   - Frontend: npm test -- --watch=false
7. If all tests pass:
   - Mark task checklist items as [x].
   - Move task file to .ai/tasks/completed/.
   - Commit changes: git commit -m "feat(<scope>): <description>"
   - Push and open PR if working with CI: git push origin feat/<task-id>-<description>
```
