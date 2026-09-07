# TASK-P15-002: Implement Read-Only Event and Session AI Tools

## 1. Task Metadata

- **Task ID:** `TASK-P15-002`
- **Git Branch:** `feat/p15-002-read-only-event-session-tools`
- **Target Module:** `backend/services/ai-service`
- **Phase:** `Phase 15 - AI Assistant & Controlled Tool Calling`
- **Depends On:** `TASK-P15-001`
- **Related Specs:** `.ai/tasks/phase-15-ai-assistant-mcp/000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-014-ai-assistant-tool-orchestration.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `4`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `database-per-service; server-side authorization; JWT propagation; correlation propagation; provider/tool isolation; read-only guarantee`

---

## 2. Objective

Implement the first controlled Spring AI tools for customer event discovery and event-session lookup:

- `searchEvents`
- `getEvent`
- `getEventSessions`

These tools must call existing SeatFlow REST APIs through Eureka/LoadBalancer. They must not query any database, duplicate Event Service business logic, invent identifiers, or expose privileged admin operations.

This task establishes the read-only domain-tool pattern used by the rest of Phase 15.

---

## 3. Scope Boundary

### In scope

- typed AI tool input/output contracts;
- load-balanced Event Service client;
- JWT + correlation propagation;
- bounded timeout/circuit-breaker behavior following existing SeatFlow client patterns;
- safe normalization of Event Service responses for model consumption;
- Spring AI tool registration;
- deterministic tool error mapping;
- unit/integration tests with mocked downstream Event Service.

### Out of scope

- seat availability;
- best-seat ranking;
- reservation creation;
- conversation memory/state machine;
- Angular UI;
- provider-specific prompting beyond minimal tool descriptions.

---

## 4. Critical Invariants

- [ ] Every tool is strictly read-only.
- [ ] `ai-service` calls Event Service APIs only through the service boundary; no database access or shared repository/entity imports.
- [ ] Downstream calls use the authenticated user's authorization context where required.
- [ ] The user's raw JWT is never inserted into the LLM prompt or tool result.
- [ ] `X-Correlation-Id` propagates through every downstream call.
- [ ] The model cannot supply an arbitrary internal service URL; destination is fixed to the logical Event Service name.
- [ ] Tool outputs contain only fields necessary for customer discovery/recommendation.
- [ ] ADMIN-only/internal fields are not included merely because an upstream DTO contains them.
- [ ] Date/time values preserve authoritative timezone/offset semantics.
- [ ] No fallback fabricates events or sessions when Event Service is unavailable.

---

## 5. Required Tool Contracts

### 5.1 `searchEvents`

Use a typed request. Recommended semantic contract:

```text
SearchEventsRequest
- query: String?                 // free-text title/name search; trimmed; bounded length
- category: String?              // existing public category/filter semantics only
- startDate: LocalDate?          // inclusive user-facing search bound
- endDate: LocalDate?            // inclusive; must be >= startDate
- limit: Integer?                // server-clamped, e.g. default 5, max 20
```

Tool rules:

- empty search criteria may map to the existing public upcoming-events behavior only if the Event Service already supports it;
- do not implement a second search engine in `ai-service`;
- normalize blank strings to absent values;
- reject malformed date ranges before downstream calls;
- cap result count before exposing data to the LLM.

Recommended result shape:

```text
SearchEventsResult
- events[]
  - eventId
  - title
  - category
  - venueName / venueId if already public
  - status
  - nextSessionStart or concise session summary if already available from API
```

Do not include large descriptions, internal audit fields, version columns, raw entities, or entire seat maps.

### 5.2 `getEvent`

```text
GetEventRequest
- eventId: UUID
```

Return a compact customer-safe event representation:

```text
EventToolResult
- eventId
- title
- descriptionSummary or bounded public description
- category
- venueId
- venueName
- status
- relevant public sales/bookability metadata already exposed by Event Service
```

Do not infer current bookability from prose; preserve upstream status semantics.

### 5.3 `getEventSessions`

```text
GetEventSessionsRequest
- eventId: UUID
- from: Instant/OffsetDateTime?   // optional
- to: Instant/OffsetDateTime?     // optional
- limit: Integer?                 // bounded
```

Return:

```text
EventSessionsToolResult
- eventId
- sessions[]
  - eventSessionId
  - startsAt
  - endsAt
  - status
  - salesStartAt/salesEndAt when already public/relevant
  - bookableHint only if derived from authoritative API data, not model judgment
```

Session IDs are the only valid inventory partition keys for later tools.

---

## 6. Downstream Client Architecture

Create a dedicated client layer, not HTTP calls inside `@Tool` methods.

Recommended separation:

```text
ai/tool/EventDiscoveryTools
        |
        v
ai/service/EventToolService
        |
        v
ai/client/EventServiceClient
        |
        v
@LoadBalanced RestClient -> http://event-service
```

The client must mirror existing SeatFlow synchronous-client conventions:

- logical Eureka service URL;
- explicit connect/read/request timeout behavior using the repo's current pattern;
- Resilience4j circuit breaker where sibling service clients use it;
- correlation propagation;
- Bearer token propagation;
- no user-controlled URL/path concatenation except validated resource IDs/query params;
- typed error mapping for `400/401/403/404/409/5xx`.

If Event Service's public contracts changed after this task was authored, use the current canonical APIs and DTOs rather than reintroducing deprecated `eventDate` semantics. Phase 12 session APIs are authoritative.

---

## 7. Security Context Propagation

Implement one reusable request-context abstraction for later tools.

It should expose only what downstream clients require, for example:

```text
AiRequestContext
- bearerToken (server-side only, never serializable to model)
- correlationId
- authenticatedSubject/userId when needed for authorization/audit
```

Rules:

- do not pass Spring Security objects into tool DTOs;
- do not log bearer token values;
- do not store bearer tokens in conversation history;
- do not send bearer tokens to Groq;
- if no authenticated identity exists, customer tools must fail according to the Phase 15 auth policy rather than silently switching to an internal privileged identity.

---

## 8. Tool Registration and Descriptions

Register tools through Spring AI's current 2.0.x tool API (`@Tool` or equivalent application-owned callbacks).

Tool descriptions must be concise and operationally precise. They should tell the model:

- when to use the tool;
- required identifiers;
- that results are authoritative snapshots;
- that failures must be reported rather than guessed.

Do **not** put security rules only in natural-language descriptions. Authorization is enforced in code/downstream services.

Do not expose Java exception class names or raw HTTP response bodies as tool output.

---

## 9. Error Contract

Map downstream failures into a small stable internal taxonomy:

```text
INVALID_TOOL_ARGUMENT
UNAUTHENTICATED
FORBIDDEN
NOT_FOUND
DOWNSTREAM_TIMEOUT
DOWNSTREAM_UNAVAILABLE
UNEXPECTED_TOOL_FAILURE
```

The model-facing result should contain a short safe message and machine-readable category where supported by the orchestration layer.

Examples:

- Event not found -> `NOT_FOUND`, no fabricated substitute.
- Event Service timeout -> `DOWNSTREAM_TIMEOUT`, assistant may ask user to retry.
- 403 -> `FORBIDDEN`, assistant must not retry with another identity.

---

## 10. Input Validation

Mandatory validation:

- UUIDs parsed/validated before calls;
- free-text query bounded (recommended <= 200 characters unless existing API has a stricter limit);
- `limit` clamped/rejected outside documented range;
- date ranges ordered correctly;
- no control characters in text search;
- no model-provided role/user identity fields;
- no internal hostname/base URL fields in tool inputs.

Input validation errors must not count as downstream failures.

---

## 11. Expected File Inventory

Create/adapt files under `backend/services/ai-service`, for example:

- `[NEW]` `.../client/EventServiceClient.java`
- `[NEW]` `.../client/dto/...` only for AI-owned transport DTOs that cannot safely reuse existing public contracts
- `[NEW]` `.../context/AiRequestContext.java`
- `[NEW]` `.../context/AiRequestContextFactory.java`
- `[NEW]` `.../tool/EventDiscoveryTools.java`
- `[NEW]` `.../tool/dto/SearchEventsRequest.java`
- `[NEW]` `.../tool/dto/SearchEventsResult.java`
- `[NEW]` `.../tool/dto/GetEventRequest.java`
- `[NEW]` `.../tool/dto/EventToolResult.java`
- `[NEW]` `.../tool/dto/GetEventSessionsRequest.java`
- `[NEW]` `.../tool/dto/EventSessionsToolResult.java`
- `[NEW]` `.../service/EventToolService.java`
- `[NEW]` focused validation/client/tool tests.

Use the repo's established package conventions if they differ. Do not duplicate an existing shared RestClient/context helper if one already provides the required secure behavior.

---

## 12. Tests

Mandatory tests:

1. `searchEvents` trims/validates criteria and maps the current public Event Service result correctly.
2. invalid date range never calls Event Service.
3. result limit is bounded.
4. `getEvent` rejects malformed UUID before downstream call.
5. `getEventSessions` returns session-scoped IDs and preserves timestamps exactly.
6. Authorization header propagates to downstream call but never appears in tool output/log assertions.
7. Correlation ID propagates.
8. 401/403/404/timeouts/5xx map to the stable error taxonomy.
9. Event Service outage never returns invented data.
10. tool classes have no dependency on Event Service repositories/entities/JPA.
11. tool results omit intentionally excluded admin/internal fields.
12. Spring AI tool metadata registers the exact intended read-only tools and no state-changing tool.

Default CI uses mocked/stubbed Event Service and does not require a Groq key.

---

## 13. Acceptance Criteria

- [ ] `searchEvents`, `getEvent`, and `getEventSessions` exist as typed, read-only controlled tools.
- [ ] Tools call current Event Service REST APIs through Eureka/LoadBalancer.
- [ ] JWT/correlation propagation is centralized and tested.
- [ ] No raw token is sent to Groq or serialized in tool output.
- [ ] Session IDs, not legacy event dates, are used for future inventory flows.
- [ ] Tool input/output size is bounded.
- [ ] Errors are deterministic and safe.
- [ ] No database or privileged admin access is introduced.
- [ ] Tool registration contains only the three read-only tools from this task.
- [ ] Tests pass without a live provider.

---

## 14. Verification

At minimum:

```bash
cd backend
mvn -pl services/ai-service -am test
```

Also run the repo's normal backend verification before completing the task. If `AI_ENABLED=true` and a Groq key is available, a manual tool-selection smoke test may be run, but it is not required for deterministic CI success.
