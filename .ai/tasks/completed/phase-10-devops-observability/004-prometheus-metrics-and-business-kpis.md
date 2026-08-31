# TASK-P10-004: Instrument Prometheus RED Metrics and Business KPIs

## 1. Task Metadata
- **Task ID:** `TASK-P10-004`
- **Git Branch:** `feat/p10-004-prometheus-metrics-business-kpis`
- **Target Module:** all Spring Boot deployables, reservation/payment/ticket/outbox domains, and `docker/prometheus`
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `AGENTS.md`, `.ai/architecture/08-observability-and-deployment.md`, `.ai/tasks/phase-10-devops-observability/001-complete-redis-integration.md`
- **Related ADRs:** `None` — the Prometheus/Micrometer stack and metrics taxonomy are specified architecture.
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants

Expose consistent RED/JVM/pool/circuit-breaker metrics and instrument the business outcomes that power operations dashboards. The task adds measurements at service boundaries; it does not derive authoritative business truth from Prometheus.

### Critical Invariants to Enforce:
- [ ] `/actuator/prometheus` is accessible only to the Docker Prometheus network and trusted Cloud Run monitoring identity; application business APIs stay JWT-protected.
- [ ] Meter tags are bounded: never use user IDs, reservation IDs, payment IDs, ticket IDs, seat IDs, client IPs, full URIs, Stripe IDs, or trace IDs.
- [ ] Counters increment only after the relevant state transition/outbox action succeeds; metrics failure must never roll back a transaction.
- [ ] PostgreSQL remains the authority for reservations/payments/tickets and Kafka plus the outbox remains the durable event mechanism.
- [ ] Existing Actuator health/info behavior, rate limiting, and all endpoint authorization remain intact.

---

## 3. Exact File Inventory

- `[MODIFY]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/config/CommonObservabilityAutoConfiguration.java` — common meter naming/tag configuration.
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/metrics/SeatFlowMetricNames.java`.
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/metrics/MetricTagPolicy.java`.
- `[NEW]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/metrics/MetricTagPolicyTest.java`.
- `[MODIFY]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/service/impl/ReservationServiceImpl.java` and `.../messaging/producer/OutboxEventPublisher.java`.
- `[MODIFY]` `backend/services/payment-service/src/main/java/com/seatflow/payment/service/impl/PaymentServiceImpl.java`, `.../service/impl/StripeWebhookServiceImpl.java`, and `.../messaging/producer/OutboxEventPublisher.java`.
- `[MODIFY]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/service/impl/TicketServiceImpl.java` and `.../messaging/producer/TicketOutboxPublisher.java`.
- `[MODIFY]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/service/impl/SeatStatusBroadcasterImpl.java` — active connection gauge only.
- `[MODIFY]` `backend/services/api-gateway/src/main/resources/application.yaml`, `backend/services/eureka-server/src/main/resources/application.yaml`, and every business service `src/main/resources/application.yaml` — bounded URI tags, histogram/SLO configuration, and Actuator exposure.
- `[MODIFY]` `backend/services/api-gateway/src/main/java/com/seatflow/gateway/config/SecurityConfig.java` (or the existing security configuration) — allow only the Prometheus scrape path according to the internal policy.
- `[MODIFY]` every business service `src/main/java/**/config/SecurityConfig.java` — same scoped Prometheus authorization where that file owns actuator authorization.
- `[MODIFY]` `docker/prometheus/prometheus.yml` — jobs for Gateway, Eureka, each of the eight services, and Prometheus self-scrape.
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/metrics/ReservationMetricsTest.java`.
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/metrics/PaymentMetricsTest.java`.
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/metrics/TicketMetricsTest.java`.
- `[NEW]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/integration/PrometheusScrapeContractTest.java`.

---

## 4. Technical Specifications & Contracts

### 4.1 Meter Names and Bounded Tags

| Meter | Type | Allowed tags | Increment/record point |
|---|---|---|---|
| `seatflow.reservations.created.total` | Counter | `status` | committed hold created |
| `seatflow.reservations.conflicts.total` | Counter | `reason` = `ALREADY_HELD`/`LIMIT_EXCEEDED`/`INVALID_STATE` | before mapped conflict response |
| `seatflow.reservations.hold.duration.seconds` | Timer | `outcome` | hold acquisition transaction completion |
| `seatflow.reservations.expired.total` | Counter | `outcome` | sweeper release committed |
| `seatflow.payments.processed.total` | Counter | `status`,`currency`,`payment_method` | payment state transition committed |
| `seatflow.tickets.issued.total` | Counter | `source` | ticket persistence committed |
| `seatflow.outbox.publish.latency.seconds` | Timer | `service`,`event_type`,`outcome` | `publishedAt - createdAt` after Kafka acknowledgement |
| `seatflow.outbox.retry.count.total` | Counter | `service`,`event_type` | retry count successfully persisted |
| `seatflow.websocket.active.connections` | Gauge | none | current realtime connection registry size |

`eventId` in the architecture is a high-cardinality anti-pattern for a production Prometheus label. Do not use it as a tag; include it only in structured logs/traces. This preserves the KPI meaning while preventing unbounded TSDB series.

### 4.2 Actuator and Histogram Configuration

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${SEATFLOW_DEPLOYMENT_ENV:local}
    distribution:
      percentiles-histogram:
        http.server.requests: true
        seatflow.reservations.hold.duration: true
        seatflow.outbox.publish.latency: true
      slo:
        http.server.requests: 50ms,100ms,250ms,500ms,1s,2s,5s
      maximum-expected-value:
        http.server.requests: 10s
```

Retain standard `http.server.requests`, JVM, HikariCP, and Resilience4j meters. Explicitly cap the HTTP URI tag to URI templates; if a raw path is not a known template, map it to `UNKNOWN` rather than emitting IDs.

### 4.3 Prometheus Scrape Contract

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
scrape_configs:
  - job_name: seatflow-gateway
    metrics_path: /actuator/prometheus
    static_configs: [{targets: ['api-gateway:8080']}]
  - job_name: seatflow-services
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['user-service:8081','seat-map-service:8082','event-service:8083','reservation-service:8084','payment-service:8085','ticket-service:8086','realtime-service:8087','notification-service:8088']
  - job_name: seatflow-eureka
    metrics_path: /actuator/prometheus
    static_configs: [{targets: ['eureka-server:8761']}]
```

### 4.4 Service Interface Contract

```java
public final class SeatFlowMetricNames {
    public static final String RESERVATIONS_CREATED = "seatflow.reservations.created";
    public static final String OUTBOX_PUBLISH_LATENCY = "seatflow.outbox.publish.latency";
}

public interface MetricTagPolicy {
    Tags reservationOutcome(String status);
    Tags paymentOutcome(String status, Currency currency, String paymentMethod);
    Tags outboxOutcome(String service, String eventType, String outcome);
}
```

Use Micrometer builder APIs, for example `Counter.builder(SeatFlowMetricNames.RESERVATIONS_CREATED).tag("status", status).register(registry).increment()`. `Timer.record(Duration)` is only called after successful publish acknowledgement or deterministic failed outcome handling.

### 4.5 Required PromQL Acceptance Queries

```promql
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))
histogram_quantile(0.95, sum by (le,application) (rate(http_server_requests_seconds_bucket[5m])))
sum(rate(seatflow_reservations_conflicts_total[5m])) by (reason)
histogram_quantile(0.99, sum by (le,service,event_type) (rate(seatflow_outbox_publish_latency_seconds_bucket[5m])))
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Checkout `feat/p10-004-prometheus-metrics-business-kpis`; baseline current meter names and security rules.
2. Create shared names/tag policy and add tests rejecting high-cardinality/unknown values.
3. Configure standardized Actuator exposure, histogram buckets, service/environment common tags, and internal-only Prometheus access.
4. Place reservation, payment, ticket, websocket, and outbox meters exactly at the state transition/publish boundaries specified above.
5. Update scrape targets and compose DNS names; do not scrape host ports from containers.
6. Write unit tests using `SimpleMeterRegistry` and a scrape contract test that asserts the required Prometheus names, types, and no forbidden labels.
7. Validate representative PromQL queries against local Prometheus after exercising reservation/payment/ticket test fixtures.

---

## 6. Definition of Done & Verification Command

To verify this task, run:

```bash
mvn -f backend/pom.xml -pl common/common-observability,services/reservation-service,services/payment-service,services/ticket-service,services/realtime-service -am test
docker compose -f docker/docker-compose.yml config
docker compose -f docker/docker-compose.yml exec prometheus promtool check config /etc/prometheus/prometheus.yml
mvn -f backend/pom.xml clean verify -B --no-transfer-progress
```

- [ ] Required RED/JVM/HikariCP/Resilience4j and business meters are visible on `/actuator/prometheus`.
- [ ] Meter tests prove correct names, bounded tags, and committed-outcome timing.
- [ ] Prometheus cannot expose application metrics on public business routes.
- [ ] Scrape configuration resolves all ten JVM DNS targets.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/004-prometheus-metrics-and-business-kpis.md`.
