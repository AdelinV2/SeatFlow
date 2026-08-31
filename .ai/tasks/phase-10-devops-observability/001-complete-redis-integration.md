# TASK-P10-001: Complete Redis Integration for Gateway Rate Limiting and Realtime Fan-Out

## 1. Task Metadata
- **Task ID:** `TASK-P10-001`
- **Git Branch:** `feat/p10-001-complete-redis-integration`
- **Target Modules:** `backend/services/api-gateway`, `backend/services/realtime-service`, `docker/`
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `.ai/SeatFlow-Architecture-and-Implementation-Spec.md`, `.ai/architecture/00-system-overview.md`, `.ai/architecture/02-microservices-spec.md`, `.ai/architecture/03-database-models.md`, `.ai/architecture/04-authentication-security.md`, `.ai/architecture/08-observability-and-deployment.md`, `AGENTS.md`, `backend/AGENTS.md`
- **Related Existing Tasks:** `.ai/tasks/completed/phase-00-foundation/006-local-infrastructure-docker.md`, `.ai/tasks/completed/phase-00-foundation/008-api-gateway.md`, `.ai/tasks/completed/phase-07-realtime-service/001-module-setup-pom-and-websocket-stomp-configuration.md`, `.ai/tasks/completed/phase-07-realtime-service/003-realtime-broadcasting-service-and-event-dtos.md`, `.ai/tasks/completed/phase-07-realtime-service/004-kafka-event-listeners-and-envelope-unwrapping.md`
- **Related ADRs:** `None` — this task implements Redis responsibilities already established by the authoritative architecture.
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective

Complete the missing **application-level Redis integration** that is already part of the SeatFlow MVP architecture.

Redis 7 infrastructure already exists locally, but the application does not currently use Redis where the architecture explicitly requires it. Implement the two justified MVP use cases:

1. **Distributed API rate limiting in `api-gateway`** with Spring Cloud Gateway `RedisRateLimiter`.
2. **Distributed realtime WebSocket fan-out in `realtime-service`** with Redis Pub/Sub between Kafka consumption and local STOMP broadcasting.

Redis must remain disposable supporting infrastructure. PostgreSQL remains authoritative for reservation correctness, seat ownership, payment state and ticket validity. Kafka + Transactional Outbox remain the durable asynchronous event backbone.

### Critical Invariants
- [ ] PostgreSQL remains the source of truth.
- [ ] Do not replace reservation PostgreSQL unique constraints, transactional locking or `FOR UPDATE SKIP LOCKED` expiration sweeper with Redis locks/TTL.
- [ ] Gateway rate-limit state is shared through Redis across Gateway instances.
- [ ] Authenticated rate-limit key uses stable JWT `sub`; anonymous fallback uses normalized client IP.
- [ ] Replenish rate, burst capacity and requested token count are externalized.
- [ ] Quota overflow returns HTTP `429 Too Many Requests` before downstream invocation.
- [ ] Kafka event consumed by one Realtime instance is published to Redis and received by every running Realtime instance.
- [ ] Kafka listeners do not also directly STOMP-broadcast the same distributed event after Redis fan-out is enabled.
- [ ] Redis Pub/Sub does not replace Kafka or Transactional Outbox.
- [ ] Redis outage cannot corrupt persisted business state.
- [ ] `local`, `docker`, `prod`, and `test` resolve Redis independently.
- [ ] No production Redis credentials are committed.
- [ ] Real Redis behavior is verified with Redis 7 Testcontainers.

---

## 3. Architecture Contract

### 3.1 API Gateway

```text
Client
  |
  v
API Gateway
  |
  +---- RedisRateLimiter ----> Redis
  |
  v
Downstream services
```

At minimum protect public/write endpoints capable of resource abuse, especially `POST /api/reservations`, payment creation, and other guest write endpoints. Health/Actuator endpoints may be excluded.

### 3.2 Realtime Fan-Out

```text
Reservation/Ticket Service
          |
          | durable domain event
          v
        Kafka
          |
          | one consumer-group delivery
          v
Realtime instance A
          |
          | Redis PUBLISH
          v
        Redis
       /  |  \
      v   v   v
    RT A RT B RT C
      |   |   |
      v   v   v
 local STOMP clients
```

Redis Pub/Sub is deliberately non-durable. A disconnected WebSocket client recovers authoritative state from REST after reconnect/refresh.

### 3.3 Explicit Non-Scope
- No Redis source-of-truth reservation state.
- No Redis distributed locks for booking correctness.
- No Redis TTL/keyspace notifications replacing the reservation expiration sweeper.
- No replacement of Kafka with Redis Streams/PubSub for durable domain events.
- No generic Event/Seat Map caching without a separate measured requirement and invalidation design.
- No HTTP session storage; SeatFlow remains stateless JWT/OIDC based.

---

## 4. Exact File Inventory

### 4.1 API Gateway
- `[MODIFY] backend/services/api-gateway/pom.xml` — add the Spring Boot 4.1-compatible reactive Redis dependency required by Gateway `RedisRateLimiter`.
- `[NEW] backend/services/api-gateway/src/main/java/com/seatflow/gateway/config/RateLimitConfig.java` — define `KeyResolver` and limiter/configuration beans if needed.
- `[MODIFY] backend/services/api-gateway/src/main/resources/application.yaml` — configure Redis-backed request rate limiting and route/default-filter wiring.
- `[MODIFY] backend/services/api-gateway/src/main/resources/application-local.yaml` — local Redis endpoint.
- `[MODIFY] backend/services/api-gateway/src/main/resources/application-docker.yaml` — Docker DNS Redis endpoint.
- `[MODIFY] backend/services/api-gateway/src/main/resources/application-prod.yaml` — managed Redis endpoint from environment only.
- `[MODIFY] backend/services/api-gateway/src/main/resources/application-test.yaml` — Testcontainers-driven Redis endpoint.
- `[MODIFY] backend/services/api-gateway/.env.example` — Redis and rate-limit variables.
- `[NEW] backend/services/api-gateway/src/test/java/com/seatflow/gateway/config/RateLimitConfigTest.java`.
- `[NEW] backend/services/api-gateway/src/test/java/com/seatflow/gateway/integration/RedisRateLimiterIntegrationTest.java`.

### 4.2 Realtime Service
- `[MODIFY] backend/services/realtime-service/pom.xml` — add Spring Data Redis support and Redis test support as required.
- `[NEW] backend/services/realtime-service/src/main/java/com/seatflow/realtime/config/RedisPubSubConfig.java` — serializer, listener container, channel/topic and subscriber wiring.
- `[NEW] backend/services/realtime-service/src/main/java/com/seatflow/realtime/dto/RedisSeatStatusEnvelope.java` — immutable transport record.
- `[NEW] backend/services/realtime-service/src/main/java/com/seatflow/realtime/service/RealtimeFanOutPublisher.java`.
- `[NEW] backend/services/realtime-service/src/main/java/com/seatflow/realtime/service/impl/RedisRealtimeFanOutPublisher.java`.
- `[NEW] backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/redis/RedisSeatStatusSubscriber.java`.
- `[MODIFY] backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/consumer/ReservationEventListener.java` — map Kafka event then publish normalized seat update to Redis instead of direct local STOMP broadcast.
- `[MODIFY] backend/services/realtime-service/src/main/java/com/seatflow/realtime/messaging/consumer/TicketEventListener.java` — same fan-out path.
- `[MODIFY IF NEEDED] backend/services/realtime-service/src/main/java/com/seatflow/realtime/service/impl/SeatStatusBroadcasterImpl.java` — retain as local STOMP adapter invoked by the Redis subscriber.
- `[MODIFY] backend/services/realtime-service/src/main/resources/application.yaml` and `application-local.yaml`, `application-docker.yaml`, `application-prod.yaml`, `application-test.yaml` — Redis/channel config.
- `[MODIFY] backend/services/realtime-service/.env.example` — Redis endpoint/channel settings.
- `[NEW] backend/services/realtime-service/src/test/java/com/seatflow/realtime/service/RedisRealtimeFanOutPublisherTest.java`.
- `[NEW] backend/services/realtime-service/src/test/java/com/seatflow/realtime/messaging/redis/RedisSeatStatusSubscriberTest.java`.
- `[NEW] backend/services/realtime-service/src/test/java/com/seatflow/realtime/integration/RedisPubSubFanOutIntegrationTest.java`.

### 4.3 Infrastructure / Docs
- `[VERIFY/MODIFY IF REQUIRED] docker/docker-compose.yml` — Redis 7 healthcheck and `redis:6379` service DNS.
- `[MODIFY IF REQUIRED] .env.example` — align root Redis defaults.
- `[MODIFY] docker/README.md` — document Gateway rate-limit state + Realtime Pub/Sub; explicitly state Redis is not the reservation source of truth.

**Do not modify `reservation-service` merely to add Redis.**

---

## 5. Technical Contracts

### 5.1 Redis Runtime Configuration

Logical environment variables:

```properties
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_SSL_ENABLED=false

RATE_LIMIT_REPLENISH_RATE=20
RATE_LIMIT_BURST_CAPACITY=40
RATE_LIMIT_REQUESTED_TOKENS=1

REALTIME_REDIS_CHANNEL=seatflow:realtime:seat-status
```

Before implementation, verify exact Spring Boot **4.1** / Spring Data Redis / Spring Cloud **2025.1** dependency names and property namespaces against official documentation. Do not copy Boot 3.x examples blindly.

| Profile | Redis endpoint |
|---|---|
| `local` | `${REDIS_HOST:localhost}:${REDIS_PORT:6379}` |
| `docker` | `${REDIS_HOST:redis}:${REDIS_PORT:6379}` |
| `prod` | environment-provided managed Redis endpoint |
| `test` | Redis Testcontainer via dynamic properties |

### 5.2 Gateway Rate-Limit Resolver

Priority:
1. authenticated JWT subject -> `user:<sub>`;
2. otherwise trusted normalized remote IP -> `ip:<address>`;
3. never null/blank.

Do not blindly trust arbitrary `X-Forwarded-For`; follow the application's proxy/forwarded-header policy.

Recommended application-owned configuration:

```yaml
seatflow:
  rate-limit:
    replenish-rate: ${RATE_LIMIT_REPLENISH_RATE:20}
    burst-capacity: ${RATE_LIMIT_BURST_CAPACITY:40}
    requested-tokens: ${RATE_LIMIT_REQUESTED_TOKENS:1}
```

Behavioral contract:

```text
same key -> shared Redis token bucket
within quota -> forwarded
over quota -> HTTP 429
different key -> independent bucket
```

Use Spring Cloud Gateway `RedisRateLimiter`; do not create a custom Redis counter algorithm unless the active framework implementation cannot satisfy the requirement.

### 5.3 Realtime Redis Envelope

Create a transport-only immutable record similar to:

```java
public record RedisSeatStatusEnvelope(
        UUID messageId,
        String originInstanceId,
        Instant publishedAt,
        SeatStatusUpdateMessage payload
) {}
```

Requirements:
- deterministic JSON serialization;
- unique `messageId` for diagnostics/tests/future deduplication;
- origin instance must still receive its own Redis-published message because it may have local clients;
- reuse the existing `SeatStatusUpdateMessage` instead of duplicating it.

### 5.4 Realtime Processing Path

```text
Kafka listener
  -> map event to SeatStatusUpdateMessage
  -> RealtimeFanOutPublisher
  -> Redis PUBLISH
  -> all Redis subscribers
  -> SeatStatusBroadcaster
  -> local /topic/events/{eventId}/seats STOMP clients
```

Subscriber requirements:
1. deserialize envelope;
2. validate required fields;
3. log message/instance/event/status/seat-count context using existing structured logging conventions;
4. call `SeatStatusBroadcaster.broadcastSeatStatus(payload)` exactly once per delivered Redis message;
5. isolate malformed messages so one bad payload cannot terminate the listener container.

Kafka listeners MUST NOT directly invoke `SeatStatusBroadcaster` for the same distributed update after Redis fan-out is enabled, otherwise the Kafka-consuming instance will double-broadcast.

### 5.5 Delivery Semantics
- Kafka + Transactional Outbox provide durability for domain events.
- Redis Pub/Sub provides low-latency best-effort fan-out only.
- Redis messages are not replayed to subscribers that were offline.
- Missing a Redis realtime message does not change persisted reservation/ticket state.
- Reconnected clients reload authoritative state through REST.

### 5.6 Observability
- Redis connectivity contributes to Actuator health where supported.
- Gateway `429` responses remain visible in standard HTTP metrics.
- Realtime adds counters:
  - `seatflow.realtime.redis.published.total`
  - `seatflow.realtime.redis.received.total`
  - `seatflow.realtime.redis.publish.errors.total`
  - `seatflow.realtime.redis.consume.errors.total`
- Do not use high-cardinality IDs as metric tags.

### 5.7 Production Contract
Architecture targets GCP Memorystore for Redis. Production configuration must come from runtime environment/secret management, keep Redis non-authoritative and support horizontal scaling of Gateway + Realtime without changing functional behavior.

---

## 6. Testing Requirements

### 6.1 Gateway Unit Test
`RateLimitConfigTest` verifies:
- authenticated principal -> `user:<sub>`;
- anonymous request -> non-empty `ip:<address>`;
- resolver never returns blank/null;
- limits bind from configuration rather than being duplicated in code.

### 6.2 Gateway Redis Integration Test
`RedisRateLimiterIntegrationTest` with Redis 7 Testcontainer:
1. start Gateway test context with dynamic Redis endpoint;
2. configure small quota, e.g. replenish `1`, burst `2`;
3. send requests using same key;
4. requests within capacity pass;
5. over-capacity request returns `429` and is not forwarded;
6. a different key has an independent bucket.

Do not make production code depend on undocumented internal Redis key names used by `RedisRateLimiter`.

### 6.3 Realtime Publisher/Subscriber Unit Tests
Verify valid envelope publishing, exact-once local subscriber invocation per delivered Redis message, validation and safe handling of malformed payloads.

### 6.4 Realtime Multi-Subscriber Integration Test
With Redis 7 Testcontainer:
1. create one publisher and two independent subscriber contexts representing two Realtime instances;
2. publish one multi-seat update;
3. assert both subscribers receive the same logical payload exactly once;
4. publish another update and prove both remain active;
5. stop one subscriber and ensure the other continues receiving;
6. verify/document that offline subscribers do not receive Pub/Sub replay.

### 6.5 Regression
All existing Gateway routing/CORS/correlation tests and Realtime Kafka/STOMP/WebSocket tests must remain green.

---

## 7. Implementation Sequence

1. Checkout `feat/p10-001-complete-redis-integration` and run baseline Gateway/Realtime tests.
2. Verify Boot 4.1 / Cloud 2025.1 Redis APIs in official docs.
3. Add Gateway reactive Redis dependency and runtime-profile configuration.
4. Implement JWT/IP rate-limit `KeyResolver` and unit tests.
5. Wire `RedisRateLimiter` to intended public/write routes and add real Redis integration test.
6. Add Realtime Redis dependency and runtime-profile/channel configuration.
7. Implement Redis envelope, publisher and subscriber.
8. Change Reservation/Ticket Kafka listeners to publish normalized seat updates to Redis, removing duplicate direct STOMP broadcast from that path.
9. Add Redis Pub/Sub unit + multi-subscriber integration tests.
10. Verify Docker Redis networking and align env/docs.
11. Run full backend verification and Docker Compose validation.

---

## 8. Definition of Done & Verification

Run from repository root:

```bash
mvn -f backend/pom.xml -pl services/api-gateway -am test
mvn -f backend/pom.xml -pl services/realtime-service -am test
mvn -f backend/pom.xml clean verify -B --no-transfer-progress
docker compose -f docker/docker-compose.yml config
```

- [ ] Gateway connects to Redis in local/docker/prod/test configurations.
- [ ] `RedisRateLimiter` protects intended routes and returns `429` on quota overflow.
- [ ] Caller rate-limit state is distributed through Redis.
- [ ] Realtime connects to Redis in all profiles.
- [ ] Reservation/Ticket Kafka updates are published to Redis Pub/Sub.
- [ ] Every running Realtime instance receives Redis updates and broadcasts to its local STOMP clients.
- [ ] Origin instance does not double-broadcast.
- [ ] Redis failure cannot corrupt PostgreSQL/Kafka business state.
- [ ] Reservation PostgreSQL locking/sweeper remains unchanged.
- [ ] Redis health/metrics/logs are observable.
- [ ] Gateway rate limiting is covered by a real Redis Testcontainers test.
- [ ] Realtime multi-instance fan-out is covered by a real Redis Testcontainers test.
- [ ] No production Redis credentials are committed.
- [ ] Full backend verification and Docker Compose validation succeed.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/001-complete-redis-integration.md`.
