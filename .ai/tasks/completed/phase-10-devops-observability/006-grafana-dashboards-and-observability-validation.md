# TASK-P10-006: Provision Grafana Dashboards and Validate Observability

## 1. Task Metadata
- **Task ID:** `TASK-P10-006`
- **Git Branch:** `feat/p10-006-grafana-dashboards-observability-validation`
- **Target Module:** `docker/grafana`, `docker/prometheus`, and monitoring Compose configuration
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `.ai/architecture/08-observability-and-deployment.md`, Tasks `P10-002`, `P10-003`, `P10-004`, and `P10-005`
- **Related ADRs:** `None` — dashboard categories and sources are already selected by architecture.
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants

Ship four versioned, auto-provisioned Grafana dashboards that use the metrics, traces and structured logs produced by previous Phase 10 tasks. Validate datasource reachability and queries against a local full-stack Compose run.

### Critical Invariants to Enforce:
- [ ] Dashboards are code-reviewed JSON/provisioning assets; no manually created dashboard is the production source of truth.
- [ ] PromQL and Tempo queries use bounded labels only and never expose PII, authentication tokens, Stripe secrets, or raw client IPs.
- [ ] Revenue/checkout panels use explicitly instrumented business metrics, not inferred database data or sums of unbounded labels.
- [ ] Logs are linked by `trace.id`; the dashboard does not require Grafana to authenticate to an application database.
- [ ] The observability stack is read-only with respect to reservation/payment/ticket state and cannot alter outbox/Kafka processing.

---

## 3. Exact File Inventory

- `[MODIFY]` `docker/grafana/provisioning/datasources/datasource.yml` — Prometheus, Tempo, and Loki (`seatflow-logs`) datasource configuration with provisioned UIDs.
- `[MODIFY]` `docker/grafana/provisioning/dashboards/dashboard-provider.yml` — folder, path, update interval and UID-safe provider settings.
- `[MODIFY]` `docker/grafana/dashboards/01-seatflow-executive-and-business.json`.
- `[MODIFY]` `docker/grafana/dashboards/02-microservices-sre-and-red-health.json`.
- `[MODIFY]` `docker/grafana/dashboards/03-kafka-and-outbox-pipeline.json`.
- `[MODIFY]` `docker/grafana/dashboards/04-security-and-auth-audit.json`.
- `[NEW]` `docker/grafana/dashboards/dashboard-schema-version.json` — dashboard schema/UID manifest used by tests to prevent accidental duplicate UIDs.
- `[MODIFY]` `docker/docker-compose.monitoring.yml` — datasource endpoints, Grafana healthcheck, Tempo, Prometheus, and Loki/Promtail services.
- `[NEW]` `docker/grafana/tests/validate-dashboards.ps1` — deterministic JSON/schema/query validation script.
- `[MODIFY]` `docker/README.md` — dashboard URLs, purpose, required demo traffic, and troubleshooting.

---

## 4. Technical Specifications & Contracts

### 4.1 Datasource Provisioning

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    uid: seatflow-prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    jsonData: {timeInterval: 15s}
  - name: Tempo
    uid: seatflow-tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:
        datasourceUid: seatflow-logs
        tags: [{key: trace.id, value: trace.id}]
  - name: SeatFlow Logs
    uid: seatflow-logs
    type: loki
    access: proxy
    url: ${GRAFANA_LOKI_URL:http://loki:3100}
    editable: false
    jsonData:
      derivedFields:
        - datasourceUid: seatflow-tempo
          matcherRegex: '"trace\.id":"([a-f0-9]{32})"'
          name: TraceID
          url: '$${__value.raw}'
```

Both local Docker Compose and production environments run Loki with Promtail, enabling full bidirectional navigation between Tempo distributed traces and structured JSON logs.

### 4.2 Required Dashboard Panels and Queries

| Dashboard | Non-negotiable panels / exact query contract |
|---|---|
| Executive & Business (`uid=seatflow-executive`) | Active holds `sum(seatflow_reservations_active_holds)`; created holds `sum(increase(seatflow_reservations_created_total[$__range]))`; expiration percentage `100 * sum(rate(seatflow_reservations_expired_total[5m])) / clamp_min(sum(rate(seatflow_reservations_created_total[5m])), 1)`; successful payments `sum(increase(seatflow_payments_processed_total{status="SUCCESS"}[$__range]))` and conversion from dedicated bounded funnel counters. |
| SRE & RED (`uid=seatflow-sre-red`) | RPS `sum(rate(http_server_requests_seconds_count[5m])) by (application)`; 5xx percentage `100 * sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (application) / clamp_min(sum(rate(http_server_requests_seconds_count[5m])) by (application),1)`; p95/p99 histogram quantiles; Hikari active/max and JVM GC pause panels; application error log stream (`{service_name=~"$application"} \| json \| level="ERROR"`). |
| Kafka & Outbox (`uid=seatflow-kafka-outbox`) | pending rows exported as `seatflow_outbox_pending`; p99 publish latency histogram; Kafka lag from a configured Kafka exporter metric; `sum(rate(seatflow_outbox_dead_letter_total[5m])) by (service,event_type)`; dead-letter log stream via Loki. |
| Security & Audit (`uid=seatflow-security-audit`) | 401 rate, Stripe webhook verification failures, Gateway 429 rate, Cloud Armor blocks if managed metric export is configured, Tempo trace search, and Loki security audit log stream keyed by `trace.id` / `user.id`. |

Dashboard JSON must set `schemaVersion`, stable `uid`, `editable: false`, UTC timezone, tags `seatflow`/`phase-10`, datasource UID references rather than display names, and template variable values limited to `application`, `environment`, `event_type`, `status`, and `payment_method`.

### 4.3 Alert Annotation Contract

Use dashboard annotations and documented alert-ready expressions (alert rule provisioning is outside this task): 5xx percentage greater than 1 for 2 minutes, pending outbox greater than 100 for 1 minute, and p99 outbox delivery greater than 30 seconds for 5 minutes. Each includes `runbook_url` pointing to `docker/README.md#observability-troubleshooting`.

### 4.4 Validation Contract

`validate-dashboards.ps1` must: parse all four JSON files; reject duplicate UIDs/panel IDs; assert each target datasource UID exists; assert each query is non-empty and has no forbidden labels (`userId`, `reservationId`, `paymentId`, `ticketId`, `traceId`, `clientIp`); call Grafana `/api/health`, `/api/search`, and Prometheus `/api/v1/query` with a known metric once Compose is running.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Checkout `feat/p10-006-grafana-dashboards-observability-validation` after P10-002 through P10-005; inspect actual emitted metric names with Prometheus before authoring panels.
2. Stabilize datasource/provider UIDs and add the dashboard manifest/validation script.
3. Update Dashboard 1 with active holds/revenue/funnel/expiration queries backed by metrics. If a metric is missing, add a bounded metric in P10-004 before proceeding rather than fabricating a query.
4. Update Dashboards 2–4 with the exact RED, outbox/Kafka, and security/tracing query contracts.
5. Configure bidirectional Tempo-to-Loki (`tracesToLogsV2`) and Loki-to-Tempo (`derivedFields`) correlation keyed by `trace.id`.
6. Start the complete Compose stack, generate deterministic reservation/payment/ticket/error fixtures, then validate Grafana health, datasources (Prometheus, Tempo, Loki), dashboard import, and no-data behavior.
7. Store screenshots only in review artifacts/CI, not in dashboard JSON or source-controlled operational secrets.

---

## 6. Definition of Done & Verification Command

To verify this task, run:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml up -d
powershell -ExecutionPolicy Bypass -File docker/grafana/tests/validate-dashboards.ps1
curl --fail http://localhost:3000/api/health
curl --fail 'http://localhost:9090/api/v1/query?query=up'
curl --fail http://localhost:3100/ready
```

- [ ] Grafana provisions four uniquely identified dashboards without manual import.
- [ ] Every panel datasource/query resolves against a running local stack or deliberately displays documented no-data state.
- [ ] RED, business, Kafka/outbox, and security/audit panels cover the required taxonomy.
- [ ] Prometheus, Tempo, and Loki datasources connect cleanly with bidirectional `trace.id` correlation.
- [ ] Trace links use `trace.id`, and dashboard/query assets contain no PII/secrets/high-cardinality labels.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/006-grafana-dashboards-and-observability-validation.md`.
