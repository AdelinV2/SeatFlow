# TASK-P10-001 Temporary Fix File

> Status: temporary implementation handoff. This file is not authoritative task or architecture documentation. Apply the decisions below to `001-complete-redis-integration.md` and the related architecture/ADR files, then delete this file.

## Verdict

`NEEDS CLARIFICATION` before implementation. The architecture direction is sound (Redis is disposable, Kafka/Transactional Outbox remain durable, and STOMP broadcasting is local), but the current task is not safe to implement without resolving the Gateway wiring, JWT trust boundary, endpoint matrix, Redis failure policy, metric names, and Pub/Sub delivery semantics.

## Release-blocking fixes

### 1. Gateway Java DSL wiring and property namespace

- `GatewayRoutesConfig` defines all routes with `RouteLocatorBuilder`; it has no YAML route definitions.
- Attach `RequestRateLimiter` directly to the intended Java DSL routes. YAML `default-filters` must not be assumed to apply to Java-built routes.
- The resolved Spring Cloud Gateway 5.0.2 source uses the prefix `spring.cloud.gateway.server.webflux`. Migrate existing Gateway properties (`default-filters`, discovery locator, routes) from the legacy `spring.cloud.gateway` namespace.
- Use `spring.cloud.gateway.server.webflux.filter.request-rate-limiter.deny-empty-key` and `...empty-key-status-code` for filter defaults.
- Keep YAML for Redis connection properties and application-owned rate-limit values; keep route/filter attachment in Java.

### 2. Verified JWT identity, never an Authorization-header fallback

- Add `spring-boot-starter-oauth2-resource-server` and a reactive Gateway `SecurityWebFilterChain`.
- Resolve `JwtAuthenticationToken`/validated principal (`Authentication#getName`, mapped from JWT `sub`) through the reactive exchange/security context.
- Never decode an unverified bearer token or use a raw JWT/header claim as the rate-limit identity.
- Invalid bearer tokens must follow the Gateway authentication policy (normally 401); requests without a bearer token use the IP fallback.

### 3. Explicit route/method matrix

Name the exact protected operations and route IDs. At minimum decide the policy for:

- `POST /api/reservations`
- `POST /api/reservations/{reservationId}/cancel`
- `POST /api/payments/intent`
- `POST /api/payments/{paymentId}/tax-preview`

Explicitly exclude `POST /api/payments/webhook` from generic payment throttling so Stripe retries are not rate-limited by caller IP. Prevent broad fallback routes from bypassing the rate-limited routes; test route order and overlap.

Document that Gateway Redis token buckets are scoped by both the caller key and route ID, not solely by `user:<sub>` or `ip:<address>` globally.

### 4. Resolver null safety and proxy trust

Resolution order:

1. validated JWT subject -> `user:<sub>`;
2. normalized client address from a trusted proxy policy -> `ip:<canonical-address>`;
3. no usable address -> `ip:unknown`, plus a low-cardinality fallback counter.

Handle `null` remote addresses, missing socket addresses, IPv4/IPv6 normalization, blank principal names, and addresses containing ports. Do not trust arbitrary `X-Forwarded-For`; define trusted ingress/proxy CIDRs and test trusted versus untrusted headers. Ensure the resolver always returns a nonblank value, while still setting `deny-empty-key: true`.

### 5. Validate rate-limit configuration

Bind a type-safe configuration object and fail startup on invalid values:

- `replenishRate >= 1`;
- `requestedTokens >= 1`;
- `burstCapacity >= replenishRate`.

Do not duplicate values in Java route code. Keep environment-backed values for all profiles.

### 6. Decide and test Redis outage behavior

The active `RedisRateLimiter` implementation fails open on Redis command errors and allows the request. This must be an explicit product/operations decision, not an accidental default.

Choose and document one policy:

- fail open: forward traffic, increment an error metric, and alert; or
- fail closed: reject rate-limited requests with a defined status while Redis is unavailable.

Test startup outage and request-time outage. For realtime, Redis publish failures must be surfaced before Kafka acknowledgement so Kafka retry behavior is deterministic. Define what happens after the configured retry/DLT recovery.

### 7. Redis connection configuration

- Gateway dependency: `org.springframework.boot:spring-boot-starter-data-redis-reactive`.
- Realtime dependency: `org.springframework.boot:spring-boot-starter-data-redis`.
- Use `spring.data.redis.host`, `port`, `username`, `password`, and `ssl.enabled`.
- Inject Boot’s `RedisConnectionFactory`; do not manually construct `LettuceConnectionFactory`, which would bypass Boot-managed credentials, TLS, pooling, and future cluster/sentinel settings.
- If pooling is enabled, add/configure `org.apache.commons:commons-pool2` with bounded values.
- In the Docker profile, `redis` resolves only for application containers joined to `seatflow-net`; workstation-run services must use the local profile and `localhost`.
- Production must obtain endpoint, credentials, TLS, timeouts, and any managed-Redis topology from runtime secret/configuration management.

### 8. Realtime listener execution and recovery

Configure `RedisMessageListenerContainer` with:

- the injected `RedisConnectionFactory`;
- a bounded `ThreadPoolTaskExecutor` for listener dispatch;
- a separate subscription executor for the long-running subscription task;
- an `ErrorHandler` that increments the consume-error counter and isolates malformed payloads/broadcaster failures;
- explicit reconnect/recovery backoff and lifecycle behavior.

Do not rely on the default unbounded `SimpleAsyncTaskExecutor`. Use the Boot/Jackson-configured mapper and verify Java time support for `Instant` and UUID values; do not create a second incompatible mapper unless required.

### 9. Pub/Sub delivery, duplicates, and ordering

`messageId` is diagnostic only if it is randomly generated per Kafka delivery attempt. Add the stable source `EventEnvelope.eventId` to the Redis envelope. Then explicitly choose one contract:

- at-least-once UI notifications, with clients tolerant of duplicate/stale updates; or
- bounded, non-authoritative deduplication keyed by stable source event ID, with TTL/capacity/eviction documented.

Do not claim exactly-once end-to-end delivery. “Exactly once” may only mean one broadcaster invocation per received Redis message.

Define ordering behavior. A `HELD`/`AVAILABLE`/`SOLD` sequence may be reordered across Kafka partitions, retries, or publishers. Add a source version/sequence or specify that clients discard stale events and REST state is authoritative after reconnect.

The origin instance must subscribe before publishing and must receive its own message. Redis Pub/Sub does not replay messages to offline subscribers.

### 10. Architecture alignment and ADR

The current architecture document lists:

- `rate:ip:{clientIp}` counters;
- `realtime:event:{eventId}:seats` Redis hashes.

This task instead introduces Gateway Lua token-bucket keys and Pub/Sub fan-out without writing an authoritative seat mirror. Create an ADR and amend/supersede those architecture rows before implementation. State explicitly that Redis Pub/Sub is best-effort and PostgreSQL remains authoritative.

### 11. Correct metric names and tags

The project uses `micrometer-registry-prometheus` 1.17.0. Do not register counter names ending in `.total`; the Prometheus client supplies the counter suffix.

Use:

```text
seatflow.realtime.redis.published
seatflow.realtime.redis.received
seatflow.realtime.redis.publish.errors
seatflow.realtime.redis.consume.errors
seatflow.gateway.rate-limit.redis.errors
seatflow.gateway.rate-limit.key-fallbacks
```

Allow only low-cardinality tags such as `eventType`, `outcome`, or `status`. Never use event IDs, seat IDs, message IDs, user IDs, or IP addresses as tags.

### 12. Health behavior

Redis contributes an Actuator `redis` health indicator. Define health groups explicitly:

- Redis must not be part of liveness, because it is non-authoritative and an outage must not create restart storms.
- Decide whether readiness degrades during Redis outage for Gateway and Realtime, and test that decision.
- Keep health details/protection consistent with the existing actuator security policy.

## Required inventory additions

Add to the task file:

- Gateway OAuth2 resource-server dependency and reactive security configuration;
- Java DSL route/filter changes in `GatewayRoutesConfig.java`;
- current Gateway 5 property namespace migration;
- endpoint/method matrix and webhook exemption;
- Redis outage policy and tests;
- bounded realtime executors, recovery backoff, and error handler;
- stable source event ID/version in the Redis envelope;
- ADR and architecture-document update;
- metric-registration test;
- `org.testcontainers:testcontainers` and `org.testcontainers:junit-jupiter` test dependencies where needed.

## Required test additions

### Gateway

- valid JWT subject, invalid JWT, anonymous request, blank principal;
- null remote address, IPv4/IPv6 normalization, trusted/untrusted forwarded headers;
- startup/request Redis outage according to the selected policy;
- two Gateway instances sharing one Redis bucket;
- rate-limit boundary and refill behavior;
- route overlap/order and Stripe webhook exemption;
- downstream probe confirms a 429 request is not forwarded;
- configuration validation and metric names/tags.

### Realtime

- JSON round trip for `Instant`, UUID, enum status, and multi-seat payload;
- malformed JSON, missing fields, unknown status, and broadcaster exception do not terminate the container;
- bounded executor and reconnect behavior;
- subscription readiness before publishing;
- two independent subscriber contexts receive one message each, one stopped subscriber does not affect the other, and offline subscribers receive no replay;
- Redis connection loss/recovery;
- Kafka retry of the same source event and the selected duplicate/order contract;
- origin instance receives the Redis message exactly once and does not direct-broadcast from the Kafka listener.

## Corrected implementation sequence

1. Resolve the endpoint matrix, JWT trust boundary, Redis outage policy, delivery/ordering contract, and ADR.
2. Migrate Gateway 5 property namespaces and add the resource-server dependency/configuration.
3. Add reactive Redis and type-safe rate-limit configuration.
4. Attach rate limiting directly to Java DSL routes; verify route order, methods, and webhook exemption.
5. Add resolver, outage, boundary, multi-instance, and forwarding tests.
6. Add realtime Redis dependency/profile configuration using Boot’s `RedisConnectionFactory`.
7. Implement the stable-ID envelope, publisher, bounded listener container, subscriber, error handling, and metrics.
8. Change Kafka listeners to publish only to Redis; retain no direct STOMP broadcast in that path.
9. Add serialization, malformed-message, recovery, multi-subscriber, duplicate/order, and offline-replay tests.
10. Align Docker/env/docs and architecture/ADR files.
11. Run module tests, full backend verification, and Docker Compose config validation.

## Implementation handoff recommendation

This is a cross-cutting Spring Cloud backend change involving JWT security, distributed rate limiting, Kafka retry semantics, Redis Pub/Sub, and observability. Use the model recommendation supplied with this handoff; do not start with a low-capability boilerplate model.
