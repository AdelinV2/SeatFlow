# TASK-P15-001: Scaffold AI Service, Groq Provider Configuration, and Security Boundary

## 1. Task Metadata

- **Task ID:** `TASK-P15-001`
- **Git Branch:** `feat/p15-001-ai-service-groq-scaffold`
- **Target Modules:** `backend/services/ai-service`, backend Maven aggregation, API Gateway, Docker/runtime configuration
- **Phase:** `Phase 15 - AI Assistant & Controlled Tool Calling`
- **Related Specs:** `.ai/tasks/phase-15-ai-assistant-mcp/000-phase-overview.md`, `.ai/architecture/09-post-mvp-evolution.md`
- **Related ADRs:** `.ai/decisions/ADR-014-ai-assistant-tool-orchestration.md`
- **Status:** `READY FOR IMPLEMENTATION`

### Orchestration Metadata

- **Complexity:** `5`
- **Failure Risk:** `High`
- **Verification Strength:** `Strong`
- **Required Review Depth:** `Critical`
- **Preferred Workflow:** `critical`
- **Affected Critical Invariants:** `secret isolation; no direct DB access; graceful AI degradation; JWT/correlation propagation; provider isolation; service discovery; no autonomous payment`

---

## 2. Objective

Create the dedicated `ai-service` on port `8090`, integrate Spring AI `2.0.1` with Groq's OpenAI-compatible endpoint, register the service with Eureka, expose only a narrow AI API surface through the gateway, and guarantee that SeatFlow remains fully usable when AI is disabled, unconfigured, rate-limited, or unavailable.

This task establishes runtime/provider/security foundations only. It must **not** implement domain tools, seat ranking, conversation orchestration, reservation confirmation, or Angular assistant UI.

---

## 3. Provider Baseline

Use Groq through Spring AI's OpenAI-compatible integration.

Required defaults:

```text
GROQ_BASE_URL=https://api.groq.com/openai/v1
GROQ_MODEL=openai/gpt-oss-20b
AI_ENABLED=false
```

Rules:

- `GROQ_API_KEY` has no default value containing a real credential.
- The model ID is configurable; application code must not depend on one concrete model.
- `openai/gpt-oss-20b` is the default because it is a Groq production model with tool support.
- Do not use preview-only model IDs as an architectural requirement.
- Do not use Groq built-in browser/code tools in this phase; SeatFlow exposes only its own controlled domain tools.
- Do not make direct Groq SDK calls from Angular or domain services.

Spring AI dependency baseline:

```xml
<spring-ai.version>2.0.1</spring-ai.version>
```

Import the Spring AI BOM in `backend/pom.xml`, then use `spring-ai-starter-model-openai` in `ai-service`.

---

## 4. Mandatory User Action Gate

### 4.1 When user configuration becomes necessary

**Manual configuration is first required at the end of this task, P15-001, for a live-provider smoke test.**

The implementation and automated tests must not require a live key, but to actually chat with Groq the project owner must:

1. create/sign in to Groq Cloud;
2. create an API key;
3. place it in the local non-versioned environment/secret location documented by the repo;
4. set `AI_ENABLED=true` only when a valid key is present;
5. for the public demo deployment, add `GROQ_API_KEY` to the production host/runtime secret environment and never to committed `.env` files.

The task must add clear documentation for these exact steps and the variables below:

```text
AI_ENABLED=true|false
GROQ_API_KEY=<secret>
GROQ_BASE_URL=https://api.groq.com/openai/v1
GROQ_MODEL=openai/gpt-oss-20b
```

Optional tuning variables may be added only when actually used and documented. Avoid a large unused configuration surface.

---

## 5. Critical Invariants & Failure Modes

### 5.1 Invariants

- [ ] `ai-service` has **no JPA, JDBC, Flyway, PostgreSQL, Redis write model, or domain database credentials**.
- [ ] No Groq key appears in source, committed `.env`, Angular environment files, Docker image layers, logs, traces, error responses, or actuator output.
- [ ] `AI_ENABLED=false` must permit `ai-service` and the rest of SeatFlow to start without a Groq key.
- [ ] Missing/invalid provider credentials must disable/fail only AI calls; core booking APIs remain unaffected.
- [ ] API Gateway exposes only the intended customer AI route prefix (recommended `/api/ai/**`) to `ai-service`.
- [ ] `ai-service` is an OAuth2 Resource Server using the same JWT validation/role mapping conventions as sibling services.
- [ ] AI service must never mint elevated roles or trust a role emitted by the LLM.
- [ ] User Bearer JWT and `X-Correlation-Id` are available for later downstream tool propagation without being added to model prompts.
- [ ] No payment endpoint or payment credential is exposed to the AI service.
- [ ] Provider timeout/5xx/429 behavior is bounded; no 10-attempt multi-minute retry loop is acceptable for interactive chat.

### 5.2 Failure modes to prevent

- application startup fails because `GROQ_API_KEY` is absent while AI is disabled;
- Groq key accidentally included in frontend bundle or Docker Compose committed defaults;
- provider 429 causes aggressive retry storm and burns remaining free quota;
- provider outage blocks Eureka registration, gateway startup, reservations, or checkout;
- AI service accidentally inherits a datasource from copied sibling configuration;
- route `/api/**` is made overly broad and shadows existing services;
- raw provider exception or authorization header is returned to browser;
- health endpoint leaks model/provider secret values.

---

## 6. Exact File Inventory

### 6.1 Required new service files

- `[NEW]` `backend/services/ai-service/pom.xml`
- `[NEW]` `backend/services/ai-service/Dockerfile`
- `[NEW]` `backend/services/ai-service/.env.example`
- `[NEW]` `backend/services/ai-service/src/main/java/com/seatflow/ai/AiServiceApplication.java`
- `[NEW]` `backend/services/ai-service/src/main/java/com/seatflow/ai/config/AiFeatureProperties.java`
- `[NEW]` `backend/services/ai-service/src/main/java/com/seatflow/ai/config/AiProviderConfig.java`
- `[NEW]` `backend/services/ai-service/src/main/java/com/seatflow/ai/config/SecurityConfig.java`
- `[NEW]` `backend/services/ai-service/src/main/java/com/seatflow/ai/api/AiStatusController.java`
- `[NEW]` `backend/services/ai-service/src/main/java/com/seatflow/ai/api/dto/AiFeatureStatusResponse.java`
- `[NEW]` `backend/services/ai-service/src/main/java/com/seatflow/ai/exception/AiProviderUnavailableException.java`
- `[NEW]` `backend/services/ai-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/ai-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/ai-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/ai-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/ai-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/ai-service/src/main/resources/logback-spring.xml`
- `[NEW]` focused startup/config/security tests under `backend/services/ai-service/src/test/...`

### 6.2 Required existing integration points

- `[MODIFY]` `backend/pom.xml` — add Spring AI version property + BOM import.
- `[MODIFY]` `backend/services/pom.xml` — add `<module>ai-service</module>`.
- `[MODIFY]` API Gateway route config + route test — route `/api/ai/**` to `lb://ai-service` without changing unrelated routes.
- `[MODIFY]` `docker/docker-compose.services.yml` — add `ai-service` port `8090` and environment wiring.
- `[MODIFY]` `docker/docker-compose.prod.yml` — add production-equivalent wiring without a committed secret.
- `[MODIFY]` repo/runtime documentation that enumerates service ports and required environment variables.
- `[MODIFY]` release verification scripts if they maintain an explicit service allow/list.

Before editing, search the current repo for `notification-service` and `analytics-service` runtime enumerations so every service-list location is covered. Do not invent a second deployment mechanism.

---

## 7. Maven / Dependency Contract

`ai-service` should reuse existing SeatFlow common modules only when needed:

- `common-domain` only for genuinely shared DTO/error primitives;
- `common-observability`;
- `common-security`;
- no `common-events` unless an actual Phase 15 event use case appears later.

Required service dependencies include the project-standard equivalents for:

- Spring MVC;
- validation;
- OAuth2 Resource Server;
- actuator;
- Eureka client;
- Spring Cloud LoadBalancer;
- Resilience4j where used for downstream service clients later;
- `spring-ai-starter-model-openai`;
- existing Micrometer/OpenTelemetry/logging dependencies;
- Spring Boot/security test modules.

Explicitly **forbid**:

- `spring-boot-starter-data-jpa`;
- PostgreSQL driver;
- Flyway;
- direct domain repositories;
- Groq Java SDK if Spring AI already covers required capability.

---

## 8. Configuration Contract

Use a SeatFlow-owned feature property such as:

```yaml
seatflow:
  ai:
    enabled: ${AI_ENABLED:false}
```

Configure Spring AI/Groq through environment placeholders. The exact Spring AI property names must follow Spring AI `2.0.1`, not older 1.x examples.

Required provider semantics:

```text
base URL = ${GROQ_BASE_URL:https://api.groq.com/openai/v1}
API key  = ${GROQ_API_KEY:}
model    = ${GROQ_MODEL:openai/gpt-oss-20b}
```

Implementation must guarantee startup without a real key. Acceptable designs include conditional provider bean creation or an equivalent tested mechanism. Do **not** rely on undocumented behavior that happens to defer credential validation.

When `AI_ENABLED=true` and the key is blank, AI status must report `MISCONFIGURED` and chat requests must return a bounded application-level `503`; do not crash the process.

### 8.1 Retry policy

Override excessive generic retry defaults for interactive requests.

Requirements:

- no retry for `400/401/403/404`;
- `429` should normally fail fast to a specific `RATE_LIMITED` response instead of a retry storm;
- at most a small bounded retry count for transient network/5xx failures;
- total interactive request latency must remain bounded by explicit timeout policy.

Do not assert exact provider free-tier limits in code.

---

## 9. Security Contract

- `/actuator/health` follows existing project policy.
- `/api/ai/status` may be readable by authenticated users and returns only feature/provider availability state, never secret/config values.
- Future chat endpoints require authenticated `USER` unless the phase later deliberately adds a guest read-only contract.
- Customer AI endpoints never expose ADMIN/STAFF tools.
- JWT stays server-side; provider prompts receive only minimum user/domain context necessary.
- Correlation ID is propagated into logs/traces and later tool calls, not prompt content.
- Add log redaction tests for `Authorization`, `GROQ_API_KEY`, and provider error bodies when those could contain echoed request metadata.

---

## 10. Health / Status Contract

Define a stable feature state enum, for example:

```text
DISABLED
READY
MISCONFIGURED
RATE_LIMITED
PROVIDER_UNAVAILABLE
```

`GET /api/ai/status` returns a safe response such as:

```json
{
  "enabled": true,
  "state": "READY",
  "model": "openai/gpt-oss-20b"
}
```

Returning the non-secret model ID is acceptable. Never return base auth headers, key fragments, full provider exceptions, account/quota metadata, or internal stack traces.

Do not make provider reachability a hard application readiness dependency; otherwise a Groq outage could remove the entire AI container from service discovery and complicate graceful disabled UI. Distinguish process health from provider availability.

---

## 11. Tests

Mandatory automated tests:

1. `AI_ENABLED=false`, no key -> application context starts.
2. `AI_ENABLED=true`, blank key -> application starts; AI status is `MISCONFIGURED`; chat-capable bean/path is unavailable safely.
3. API key is not serialized in status DTO, error DTO, or logs captured by tests.
4. Gateway route matches `/api/ai/**` and does not shadow unrelated `/api/**` routes.
5. Unauthenticated access to protected AI endpoint returns the project-standard unauthorized response.
6. USER can reach status endpoint according to chosen policy; ADMIN role does not create additional customer tools.
7. No datasource auto-configuration/dependency is present in `ai-service`.
8. Mocked provider 401/429/5xx maps to stable internal error categories without raw provider body leakage.
9. Docker/config validation confirms no real key is required in committed configuration.

A real Groq network call must **not** run in default CI.

---

## 12. Acceptance Criteria

- [ ] `ai-service` builds in the backend multi-module build.
- [ ] Spring AI 2.0.1 dependency management is added once at the root.
- [ ] Groq is configured through the OpenAI-compatible Spring AI integration.
- [ ] Default model is configurable and defaults to `openai/gpt-oss-20b`.
- [ ] Service registers with Eureka and is routed narrowly through API Gateway.
- [ ] No database dependency/config exists in `ai-service`.
- [ ] SeatFlow and `ai-service` start with AI disabled and no Groq key.
- [ ] Enabling AI without a key yields safe `MISCONFIGURED`, not startup failure.
- [ ] Secret handling is documented and tested.
- [ ] Provider failures are bounded and do not affect booking/payment services.
- [ ] Manual Groq setup steps are documented for the project owner.
- [ ] No domain tools or chat orchestration are prematurely implemented.

---

## 13. Verification Commands

Use the repo's current standard commands. At minimum verify:

```bash
cd backend
mvn -pl services/ai-service -am test
mvn verify
```

Validate relevant Docker Compose config and gateway route tests using existing repo scripts.

For the optional manual provider smoke test after the user supplies a key:

```text
AI_ENABLED=true
GROQ_API_KEY=<secret>
GROQ_MODEL=openai/gpt-oss-20b
```

The smoke test should perform one minimal text generation only. Full tool-calling validation belongs to later tasks.
