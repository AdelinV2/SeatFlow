# TASK-P10-003: Add OpenTelemetry Tracing and W3C Context Propagation

## 1. Task Metadata
- **Task ID:** `TASK-P10-003`
- **Git Branch:** `feat/p10-003-opentelemetry-w3c-context-propagation`
- **Target Module:** `backend/common/common-observability`, `backend/common/common-events`, all JVM deployables, and `docker/otel`
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `AGENTS.md`, `.ai/architecture/08-observability-and-deployment.md`, `.ai/tasks/phase-10-devops-observability/002-production-structured-json-logging-and-trace-context.md`
- **Related ADRs:** `None` — OpenTelemetry and W3C propagation are already mandated by the architecture.
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants

Provide one trace across Gateway-to-service HTTP calls and producer-to-consumer Kafka delivery. Run the OpenTelemetry Java agent at container startup, bridge its context to Micrometer Tracing, and carry W3C `traceparent`/`tracestate` through the existing `EventEnvelope` header map without changing payload schemas.

### Critical Invariants to Enforce:
- [ ] W3C values use the exact lowercase header names `traceparent` and `tracestate`; malformed/untrusted values are ignored and produce a new root trace.
- [ ] All Kafka payloads remain `EventEnvelope<T>`; trace context travels only in envelope headers and never replaces `X-Correlation-Id`.
- [ ] Kafka consumer MDC is scoped to the listener invocation and cleared/restored even if deserialization or business processing fails.
- [ ] OTLP endpoints and credentials are environment variables; no collector URL or token is hardcoded in Java.
- [ ] Tracing must be best-effort: exporter failures cannot roll back a business transaction, outbox write, or Kafka acknowledgement decision.

---

## 3. Exact File Inventory

- `[MODIFY]` `backend/pom.xml` — add managed OpenTelemetry/Micrometer tracing dependencies and the agent version property.
- `[MODIFY]` `backend/common/common-observability/pom.xml` — add Micrometer tracing bridge and OpenTelemetry API dependencies.
- `[MODIFY]` `backend/common/common-events/src/main/java/com/seatflow/common/events/EventHeaders.java` — add `TRACEPARENT` and `TRACESTATE` constants.
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/tracing/W3cTraceContextPropagator.java`.
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/tracing/KafkaTraceContext.java`.
- `[MODIFY]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/config/CommonObservabilityAutoConfiguration.java`.
- `[NEW]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/tracing/W3cTraceContextPropagatorTest.java`.
- `[NEW]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/tracing/KafkaTraceContextTest.java`.
- `[MODIFY]` every business service `src/main/java/**/messaging/producer/*Outbox*Publisher.java` — inject W3C headers into new envelopes before Kafka send.
- `[MODIFY]` every business service and `realtime-service` `src/main/java/**/messaging/consumer/*Listener.java` — extract parent trace and establish listener MDC/scope.
- `[MODIFY]` `backend/services/api-gateway/src/main/resources/application.yaml` and `backend/services/eureka-server/src/main/resources/application.yaml` — base tracing defaults.
- `[MODIFY]` all ten deployable modules' `src/main/resources/application-prod.yaml` and `application-docker.yaml` — environment-derived OTLP configuration.
- `[NEW]` `docker/otel/otel-collector-config.yaml` — OTLP receiver and Tempo exporter pipeline.
- `[NEW]` `docker/otel/Dockerfile.agent` — downloads/pins the OpenTelemetry Java agent in the build stage, with SHA-512 verification.
- `[NEW]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/integration/KafkaW3cPropagationIntegrationTest.java` — Testcontainers Kafka proof.

---

## 4. Technical Specifications & Contracts

### 4.1 Runtime Configuration

```yaml
management:
  tracing:
    sampling:
      probability: ${OTEL_TRACES_SAMPLER_ARG:1.0}
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://otel-collector:4318/v1/traces}
otel:
  service:
    name: ${SEATFLOW_SERVICE_NAME:${spring.application.name}}
  resource:
    attributes: deployment.environment=${SEATFLOW_DEPLOYMENT_ENV:docker},service.version=${SEATFLOW_VERSION:dev}
```

Container JVM options are exactly:

```text
-javaagent:/opt/opentelemetry/opentelemetry-javaagent.jar
-Dotel.exporter.otlp.protocol=${OTEL_EXPORTER_OTLP_PROTOCOL:http/protobuf}
-Dotel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://otel-collector:4318}
-Dotel.propagators=tracecontext,baggage
-Dotel.logs.exporter=none
```

The collector listens on `0.0.0.0:4317` (`otlp` gRPC) and `0.0.0.0:4318` (`otlp` HTTP), then exports traces to `http://tempo:4318` using OTLP/HTTP. Tempo query remains `http://tempo:3200` for Grafana.

### 4.2 W3C Envelope Contract

```java
public final class EventHeaders {
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
}

public interface W3cTraceContextPropagator {
    void inject(Map<String, String> headers);
    Context extract(Map<String, String> headers);
}
```

`inject` adds a valid `traceparent` from `Context.current()` and copies `tracestate` when present. It leaves a pre-existing application correlation ID intact. `extract` validates the version, 32-hex trace ID, 16-hex parent ID, and sampled flags; invalid input returns `Context.root()` and logs only a redacted diagnostic (never raw header contents).

### 4.3 Kafka Listener Contract

```java
try (Scope scope = propagator.extract(envelope.headers()).makeCurrent();
     MdcScope mdc = kafkaTraceContext.putCurrentTraceAndCorrelation(envelope.headers())) {
    delegate.handle(envelope);
}
```

Producer code must create the envelope/header map within the span that writes/publishes the event. Consumer instrumentation creates a `CONSUMER` span named `kafka consume <topic>` with the extracted context as parent, tags only low-cardinality `messaging.system=kafka`, `messaging.destination.name`, `messaging.operation=process`, and `seatflow.event_type`.

### 4.4 HTTP Contract

Gateway must forward incoming `traceparent`, `tracestate`, and `baggage` unchanged when valid. RestClient/WebClient instrumentation creates child spans automatically; do not manually generate a second trace ID or add service host/port tags that expose topology. The exposed link from a JSON log uses `trace.id` and `span.id` produced by the active span.

### 4.5 Verification Matrix

| Hop | Assertion |
|---|---|
| client -> gateway -> reservation | one 32-hex trace ID; gateway and reservation span IDs differ |
| reservation outbox -> Kafka -> ticket | envelope carries valid `traceparent`; consumer span has producer trace as ancestor |
| malformed `traceparent` | request/event completes with a new root trace and no stack trace |
| collector unavailable | normal request/event outcome is unaffected; exporter failure is observable |

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Checkout `feat/p10-003-opentelemetry-w3c-context-propagation` after P10-002 is integrated; verify active Boot 4.1 and Micrometer dependency names against official Spring Boot and OpenTelemetry documentation.
2. Add the managed dependencies and auto-configured propagator; avoid a separate tracing abstraction per service.
3. Extend common event headers, update every envelope-producing outbox path, and place consumer extraction around every event listener.
4. Configure HTTP propagation and OTLP endpoint properties in base/docker/prod profiles.
5. Add the pinned agent image layer and collector configuration; the final images must launch with the agent but not bake tokens into layers.
6. Write focused unit tests for exact injection/extraction validation and a Kafka Testcontainers integration test that asserts parent-child continuity using an in-memory span exporter or collector test receiver.
7. Start collector/Tempo locally, submit one Gateway request and one Kafka event, and verify the trace tree and correlated MDC fields.

---

## 6. Definition of Done & Verification Command

To verify this task, run:

```bash
mvn -f backend/pom.xml -pl common/common-observability,common/common-events -am test
mvn -f backend/pom.xml -pl services/reservation-service,services/ticket-service,services/realtime-service -am test
docker compose -f docker/docker-compose.yml -f docker/docker-compose.monitoring.yml config
mvn -f backend/pom.xml clean verify -B --no-transfer-progress
```

- [ ] HTTP and Kafka tests prove W3C parent context injection/extraction.
- [ ] Testcontainers Kafka test proves valid envelope headers survive the producer/consumer boundary.
- [ ] No invalid header becomes a trace ID, no exporter failure alters business processing, and no secrets appear in OTLP configuration/logs.
- [ ] Agent, collector, and Tempo endpoints use ports 4317, 4318, and 3200 exactly as specified.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/003-opentelemetry-distributed-tracing-and-w3c-context.md`.
