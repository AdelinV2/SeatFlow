# TASK-P10-002: Standardize Production JSON Logging and Trace Context

## 1. Task Metadata
- **Task ID:** `TASK-P10-002`
- **Git Branch:** `feat/p10-002-production-json-logging-trace-context`
- **Target Module:** `backend/common/common-observability`, all services, `api-gateway`, and `eureka-server`
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `AGENTS.md`, `.ai/architecture/08-observability-and-deployment.md`, `.ai/tasks/phase-10-devops-observability/001-complete-redis-integration.md`
- **Related ADRs:** `None` — this implements the logging architecture already selected in the specification.
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants

Make every JVM process emit a consistent ECS/Logstash-compatible JSON event in `prod`, while retaining human-readable output in `local` and quiet test logging. Extend the existing shared MDC filter so request logs carry correlation, authenticated-user, HTTP, and Micrometer trace context without leaking credentials.

### Critical Invariants to Enforce:
- [ ] Never log PAN/card values, CVV, Stripe `sk_*` or `whsec_*` secrets, JWT bearer tokens, passwords, authorization headers, or verification/reset tokens.
- [ ] JSON fields are exactly `trace.id`, `span.id`, `correlation.id`, `user.id`, `http.method`, `http.uri`, and `http.client_ip`; request MDC is always cleared in `finally`.
- [ ] The shared `common-observability` module remains the sole source of request MDC/filter behavior; no service creates a `@RestControllerAdvice`.
- [ ] Logs use stable event-oriented messages and named key/value context; no sensitive values are embedded in exception messages.
- [ ] Production logging must not change authorization, reservation locking, outbox transactionality, or persisted state.

---

## 3. Exact File Inventory

- `[MODIFY]` `backend/common/common-observability/pom.xml` — retain the Logstash encoder and add only the test dependency required by masking/MDC tests.
- `[MODIFY]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/filter/MdcLoggingFilter.java` — populate/restore MDC safely and use dotted field names.
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/logging/SensitiveDataMaskingConverter.java` — one reusable Logback converter for message/stack-trace masking.
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/logging/StructuredLogFields.java` — constants for all MDC names and approved taxonomy keys.
- `[MODIFY]` `backend/common/common-observability/src/main/resources/logback-spring.xml` — production JSON encoder and local/test appenders.
- `[NEW]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/logging/SensitiveDataMaskingConverterTest.java`.
- `[MODIFY]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/filter/MdcLoggingFilterTest.java`.
- `[MODIFY]` `backend/services/api-gateway/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/eureka-server/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/user-service/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/seat-map-service/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/event-service/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/reservation-service/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/payment-service/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/ticket-service/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/realtime-service/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/notification-service/src/main/resources/logback-spring.xml`.
- `[MODIFY]` `backend/services/api-gateway/src/main/resources/application-prod.yaml`, `backend/services/eureka-server/src/main/resources/application-prod.yaml`, and every business service `src/main/resources/application-prod.yaml` — set the service/environment properties consumed by logging.

---

## 4. Technical Specifications & Contracts

### 4.1 Structured JSON and Masking Contract

Use a production-only `LogstashEncoder` with the following providers. The converter is applied before output and must mask both message and throwable text.

```xml
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
  <customFields>{"service.name":"${SEATFLOW_SERVICE_NAME}","service.environment":"${SPRING_PROFILES_ACTIVE:-prod}"}</customFields>
  <includeMdcKeyName>trace.id</includeMdcKeyName><includeMdcKeyName>span.id</includeMdcKeyName>
  <includeMdcKeyName>correlation.id</includeMdcKeyName><includeMdcKeyName>user.id</includeMdcKeyName>
  <includeMdcKeyName>http.method</includeMdcKeyName><includeMdcKeyName>http.uri</includeMdcKeyName>
  <includeMdcKeyName>http.client_ip</includeMdcKeyName>
  <maskingPattern>(?i)(Bearer\\s+eyJ[^\\s,]+|sk_(?:live|test)_[A-Za-z0-9_]+|whsec_[A-Za-z0-9_]+|password=[^\\s,&]+|token=[^\\s,&]+|\\b(?:\\d[ -]*?){13,16}\\b)</maskingPattern>
</encoder>
```

Mask replacements are `Bearer [MASKED_JWT]`, `[MASKED_STRIPE_SECRET]`, `[MASKED]`, and `****-****-****-` plus the final four PAN digits. Preserve non-secret diagnostic context.

### 4.2 Request MDC Contract

`MdcLoggingFilter` has order after Spring Security. It reads `X-Correlation-Id` only if it is a valid UUID; otherwise generate a UUID. It writes:

```java
MDC.put("correlation.id", correlationId);
MDC.put("http.method", request.getMethod());
MDC.put("http.uri", request.getRequestURI());
MDC.put("http.client_ip", resolvedClientIp);
authentication.ifPresent(a -> MDC.put("user.id", a.getName()));
tracer.currentSpan().context().traceId(); // -> trace.id when valid
tracer.currentSpan().context().spanId();  // -> span.id when valid
```

Resolve client IP from the servlet/container remote address unless the application has explicitly enabled trusted forwarded-header support; never trust an arbitrary client-supplied `X-Forwarded-For`. Preserve any pre-existing MDC map and restore it in `finally` so nested dispatches and async hand-off code do not erase outer context.

### 4.3 Log Taxonomy Contract

Builders must use these levels and fields: successful hold/payment/ticket lifecycle `INFO`; hold collision, duplicate Stripe webhook and rate-limit rejection `WARN`; outbox delivery failure `ERROR`; outbox persistence/polling `DEBUG`. Approved domain context uses `eventId`, `reservationId`, `paymentId`, `ticketId`, `outboxId`, `eventType`, `seatsCount`, `durationMs`, and `retryCount`; it must never use raw `seatIds` if that conflicts with privacy policy.

### 4.4 Configuration Contract

Every production profile supplies, without hardcoding a secret:

```yaml
seatflow:
  observability:
    service-name: ${SEATFLOW_SERVICE_NAME:${spring.application.name}}
logging:
  config: classpath:logback-spring.xml
```

### 4.5 Service Interface Contract

```java
public final class StructuredLogFields {
    public static final String TRACE_ID = "trace.id";
    public static final String SPAN_ID = "span.id";
    public static final String CORRELATION_ID = "correlation.id";
    public static final String USER_ID = "user.id";
    public static final String HTTP_METHOD = "http.method";
    public static final String HTTP_URI = "http.uri";
    public static final String HTTP_CLIENT_IP = "http.client_ip";
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Checkout `feat/p10-002-production-json-logging-trace-context`; run the existing common-observability tests.
2. Introduce shared field constants and update the filter to populate dotted fields, trace/span values when tracing is available, validated correlation IDs, and safe MDC restoration.
3. Implement the masking converter with compiled patterns and unit tests for card, Stripe, JWT, password/token, benign text, and throwable content.
4. Replace the shared production encoder with the specified JSON mapping; keep readable local/default output and silent/test-safe configuration.
5. Align the ten application logback files with the shared format and add production service-name configuration.
6. Update the named lifecycle logs in reservation, payment, ticket, gateway and outbox publishers to the taxonomy; do not change business decisions.
7. Assert emitted JSON contains required keys, masking occurs before output, authentication contributes `user.id`, and MDC is absent after filter completion.

---

## 6. Definition of Done & Verification Command

To verify this task, run:

```bash
mvn -f backend/pom.xml -pl common/common-observability -am test
mvn -f backend/pom.xml -pl services/api-gateway,services/reservation-service,services/payment-service,services/ticket-service -am test
mvn -f backend/pom.xml clean verify -B --no-transfer-progress
```

- [ ] Each production process emits valid JSON with the seven required correlation/trace/request fields when available.
- [ ] Card, Stripe, JWT, password, and token fixtures are masked in message and stack trace output.
- [ ] Local output remains readable and tests do not require an external collector.
- [ ] MDC is restored on success, exception, and async dispatch.
- [ ] No root exception handler was added to a microservice.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/002-production-structured-json-logging-and-trace-context.md`.
