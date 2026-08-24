# TASK-XXX: [Short Action-Oriented Title]

## 1. Task Metadata
- **Target Module:** `backend/services/<service-name>` OR `frontend/src/app/...`
- **Phase:** `Phase X - [Phase Name]`
- **Related Specs:** `.ai/architecture/XX-[spec-name].md`
- **Related ADRs:** `.ai/decisions/ADR-XXX-[decision].md`
- **Status:** `READY FOR IMPLEMENTATION` <!-- Options: DRAFT, READY FOR IMPLEMENTATION, IN PROGRESS, COMPLETED -->

---

## 2. Objective & Invariants
Brief description of what this task delivers.

### Critical Invariants to Enforce:
- [ ] Invariant 1 (e.g. Max 10 seats per reservation)
- [ ] Invariant 2 (e.g. Idempotency key required on creation)
- [ ] Invariant 3 (e.g. No direct Kafka publishing — use Outbox)

---

## 3. Exact File Inventory
List all files to create, modify, or delete.

- `[NEW]` `resources/db/migration/V1__create_example_table.sql`
- `[NEW]` `model/entity/Example.java`
- `[NEW]` `repository/ExampleRepository.java`
- `[NEW]` `service/ExampleService.java`
- `[NEW]` `service/impl/ExampleServiceImpl.java`
- `[NEW]` `mapper/ExampleMapper.java`
- `[NEW]` `web/dto/request/CreateExampleRequest.java`
- `[NEW]` `web/dto/response/ExampleResponse.java`
- `[NEW]` `web/controller/ExampleController.java`
- `[NEW]` `test/java/com/seatflow/.../ExampleServiceTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Database Schema (Flyway SQL)
```sql
CREATE TABLE example (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 4.2 DTO Records & Signatures
```java
public record CreateExampleRequest(
    @NotNull UUID id
) {}

public record ExampleResponse(
    UUID id
) {}
```

### 4.3 Service Interface Contract
```java
public interface ExampleService {
    ExampleResponse createExample(CreateExampleRequest request, String userId);
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Step 1 - Database Migration
2. Step 2 - Entity & Repository  
3. Step 3 - Request/Response DTOs & MapStruct Mapper
4. Step 4 - Service Layer (Interface + Implementation)
5. Step 5 - Controller
6. Step 6 - Testing

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -Dtest=ExampleServiceTest,ExampleIntegrationTest
```
- [ ] Code compiles without warnings.
- [ ] All unit and slice tests pass.
- [ ] Task file is moved to `.ai/tasks/completed/<phase-name>/`.
