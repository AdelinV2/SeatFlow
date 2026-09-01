# SeatFlow Local Infrastructure & Compose Stack

Containerized developer infrastructure and full-stack orchestration for SeatFlow, provisioned with Docker Compose v2.

## Architecture Split

Local orchestration is intentionally split into composable files that mirror the single-VM production topology:

| File | Purpose | Key Services |
|------|---------|--------------|
| `docker/docker-compose.yml` | Core infrastructure + service discovery + edge gateway | `postgres:16.6-alpine`, `apache/kafka:3.9.0` (KRaft), `redis:7.4-alpine`, `eureka-server`, `api-gateway` |
| `docker/docker-compose.services.yml` | Eight business microservices + Angular/Nginx frontend | `user-service:8081` … `notification-service:8088`, `frontend:8080` |
| `docker/docker-compose.monitoring.yml` | Self-hosted observability stack | `otel-collector:4317/4318`, `prometheus:9090`, `grafana:3000`, `tempo:3200`, `loki:3100`, `promtail`, `kafka-exporter` |
| `docker/docker-compose.prod.yml` | **Production override** for GCP `e2-highmem-2` (2 vCPU / 16 GiB) | Immutable AR images, `prod` profile, private ports, resource limits, persistence, security hardening |

Production is the same composition rendered with the override:

```bash
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.services.yml \
  -f docker/docker-compose.monitoring.yml \
  -f docker/docker-compose.prod.yml config
```

## Prerequisites

- Docker Engine 24+ with the Compose plugin (`docker compose` v2).
- Copy the root `.env.example` to `.env` and adjust credentials if desired.

```bash
cp .env.example .env
```

## Local Execution

### Core only (infra + Eureka + Gateway)

```bash
docker compose -f docker/docker-compose.yml up -d --build
docker compose -f docker/docker-compose.yml ps
```

### Full application (adds business services + frontend)

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml up -d --build
```

### Full stack with observability

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml up -d
```

### Validate composition (CI)

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml config --quiet
```

### Production rendering (requires immutable tag)

```bash
SEATFLOW_IMAGE_TAG=test AR_BASE=example.invalid/seatflow \
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml -f docker/docker-compose.prod.yml config --quiet
```

### Tail logs / diagnostics

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml logs -f
docker compose -f docker/docker-compose.yml ps
docker inspect seatflow-postgres --format='{{json .State.Health.Status}}'
curl -s http://localhost:8080/actuator/health | jq
curl -s http://localhost:8761/actuator/health | jq
```

### Stop / reset

```bash
# Stop and remove containers (keeps volumes)
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml down

# Stop and remove containers AND volumes (hard reset, drops all data)
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml down -v
# WARNING: deletes pg_data & kafka_data - PostgreSQL and Kafka state is lost.
```

## Component Inventory

| Service | Image (local) | Port (host, local) | Port (prod) | Purpose |
|---------|---------------|--------------------|-------------|---------|
| postgres | `postgres:16.6-alpine` | 5432 | private (`expose: 5432`) | 7 logical databases (per-service ownership) |
| redis | `redis:7.4-alpine` | 6379 | private | Gateway rate-limit + Realtime Pub/Sub (non-authoritative) |
| kafka | `apache/kafka:3.9.0` | 9092 | private | KRaft event backbone (Transaction Outbox) |
| eureka-server | `seatflow/eureka-server:local` | 8761 | private | Service discovery (Eureka + LoadBalancer) |
| api-gateway | `seatflow/api-gateway:local` | 8080 | private | Edge routing, JWT auth, rate limiting |
| user-service | `seatflow/user-service:local` | 8081 | private | Identity & profiles |
| seat-map-service | `seatflow/seat-map-service:local` | 8082 | private | Venue & layout |
| event-service | `seatflow/event-service:local` | 8083 | private | Event catalog |
| reservation-service | `seatflow/reservation-service:local` | 8084 | private | Holds & 15-min sweeper |
| payment-service | `seatflow/payment-service:local` | 8085 | private | Stripe adapter |
| ticket-service | `seatflow/ticket-service:local` | 8086 | private | QR issuance |
| realtime-service | `seatflow/realtime-service:local` | 8087 | private | WebSocket STOMP |
| notification-service | `seatflow/notification-service:local` | 8088 | private | Email / notification fan-out |
| frontend | `seatflow/frontend:local` | 4200 → 8080 | **80 public** | Angular SPA + Nginx reverse proxy (`/api/`, `/ws/`) |
| otel-collector | `otel/opentelemetry-collector-contrib:0.128.0` | 4317/4318 | private | OTLP → Tempo |
| prometheus | `prom/prometheus:v2.51.0` | 9090 | private | `/actuator/prometheus` scraping (Docker DNS) |
| grafana | `grafana/grafana:10.4.0` | 3000 | private | Dashboards (Trace-to-Log) |
| tempo | `grafana/tempo:2.4.1` | 3200 | private | Distributed traces |
| loki | `grafana/loki:3.0.0` | 3100 | private | Structured JSON logs |
| promtail | `grafana/promtail:3.0.0` | — | — | Ships the Compose-scoped SeatFlow Docker stream to Loki |
| kafka-exporter | `danielqsj/kafka-exporter:v1.8.0` | 9308 | private | Exposes bounded Kafka consumer-group lag metrics to Prometheus |

Only the Nginx edge (`frontend`) publishes a public port in production: host port 80 to its unprivileged internal port 8080. All other ports are `expose`-only inside `seatflow-net`.

## Database Layout

PostgreSQL auto-creates 7 databases via `docker/init-db/01-init-multiple-dbs.sql`:

`seatflow_user`, `seatflow_seatmap`, `seatflow_event`, `seatflow_reservation`,
`seatflow_payment`, `seatflow_ticket`, `seatflow_notification`.

Each microservice connects to its own database using `DB_HOST=postgres` in `docker`/`prod` profiles.

Reproducible dumps: `pg_dump` from the running container; restores are forward-only Flyway migrations.

## Networking & Edge

- All services attach to the private bridge `seatflow-net`.
- Frontend Nginx is the sole public entrypoint in prod:
  - SPA fallback to `index.html`
  - `location /api/` → `proxy_pass http://api-gateway:8080`
  - `location /ws/` + `Upgrade`/`Connection` → WebSocket/STOMP
  - Security headers (`HSTS`, `X-Frame-Options`, `X-Content-Type-Options`, `CSP`), correlation propagation, hashed-asset caching (`immutable`) vs `no-cache` for `index.html`, and `Authorization` excluded from access logs.
- Eureka remains the synchronous HTTP discovery contract (`@LoadBalanced RestClient.Builder` → `http://<service-name>`); no Kubernetes DNS.

## Observability

- **Traces:** `JAVA_TOOL_OPTIONS=-javaagent:/opt/opentelemetry/opentelemetry-javaagent.jar` → OTLP `http://otel-collector:4318` → `otlphttp/tempo` (`http://tempo:4318`) → Grafana Tempo datasource.
- **Metrics:** Micrometer `/actuator/prometheus` → Prometheus scraping `api-gateway:8080`, `eureka-server:8761`, `user-service:8081` … via Docker DNS (`docker/prometheus/prometheus.yml`). `PHYSICAL` metric relabel restores `seatflow_reservations_created_total`.
- **Logs:** Structured JSON stdout → Docker json-file (rotated `max-size:10m`, `max-file:3`) → Grafana Promtail (`docker.sock`, one Compose-project-scoped source, persistent positions) → Loki (`/loki`) → Grafana Loki datasource with Trace-to-Log correlation through the JSON `trace.id` field. Promtail keeps trace IDs, user identifiers, reservation identifiers, payment identifiers, ticket identifiers, and network addresses out of Loki labels.
- **Kafka lag:** `kafka-exporter:9308` is scraped by Prometheus for consumer-group lag panels; the exporter and all dashboard data sources are read-only.
- **Retention & resources (portfolio VM):** Prometheus 7d / 2GB, Tempo 7d local, Loki 7d (168h), all bounded to ~128-512 MiB RAM per component. JVM heaps: gateway/eureka 256-384 MiB, most services 256-384 MiB, reservation/event up to 512 MiB, total < 8 GiB leaving headroom on 16 GiB host.
- Access Grafana locally at <http://localhost:3000>. Change the example `admin` password before any non-local deployment; production refuses to start without `GRAFANA_ADMIN_PASSWORD`.

## Grafana Dashboards

The dashboards are provisioned from reviewed JSON files in `docker/grafana/dashboards/`. They are immutable in the Grafana UI (`editable: false`) and use stable UIDs so links remain valid across container recreation. The local URLs are:

| Dashboard | URL | Purpose |
|-----------|-----|---------|
| Executive & Business | <http://localhost:3000/d/seatflow-executive/> | Active holds, hold expirations, reservation funnel conversion, and successful payments |
| Microservices SRE & RED Health | <http://localhost:3000/d/seatflow-sre-red/> | Request rate, 5xx rate, p95/p99 latency, Hikari pools, JVM GC pauses, and error logs |
| Kafka & Outbox Pipeline | <http://localhost:3000/d/seatflow-kafka-outbox/> | Outbox backlog, publish latency, Kafka consumer lag, dead-letter rate, and dead-letter logs |
| Security & Auth Audit | <http://localhost:3000/d/seatflow-security-audit/> | HTTP 401s, Stripe webhook failures, gateway 429s, trace search, and security audit logs |

Use the `seatflow-production` folder and the provisioned datasource UIDs `seatflow-prometheus`, `seatflow-tempo`, and `seatflow-logs` when diagnosing provisioning. Business panels intentionally use the instrumented `seatflow_reservations_*` and `seatflow_payments_*` metrics; they do not query application databases directly.

### Required demo traffic

To populate the panels, run a representative flow through the frontend/API gateway:

- Create and confirm reservations, plus at least one abandoned hold that expires after the 15-minute hold window.
- Complete a successful payment and exercise a failed payment or invalid Stripe webhook signature.
- Generate an outbox publish, retry, and dead-letter case where the local service/test fixtures support it.
- Make an unauthenticated request, trigger a gateway rate limit (`429`), and create a controlled service error for the SRE error panels.
- Exercise one authenticated request with tracing enabled, then open a Loki log line and follow its `trace.id` link to Tempo (and the reverse Tempo-to-Loki link).

## Observability Troubleshooting

Start with container health and the provisioning validator:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml ps
powershell -ExecutionPolicy Bypass -File docker/grafana/tests/validate-dashboards.ps1
```

The validator checks all four dashboard JSON files, datasource UID references, duplicate panel/dashboard UIDs, bounded query labels, and the live Grafana/Prometheus APIs. If the live checks fail, verify the endpoints directly:

```bash
curl -s http://localhost:3000/api/health
curl -s -u admin:admin 'http://localhost:3000/api/search?type=dash-db'
curl -s 'http://localhost:9090/api/v1/query?query=up'
curl -s http://localhost:3100/ready
curl -s http://localhost:3200/ready
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml logs --tail=100 grafana prometheus tempo loki promtail kafka-exporter
```

Promtail's live health probe uses its internal port 9080 `/ready` endpoint. Check it from the running container with `docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml exec promtail wget -q --spider http://127.0.0.1:9080/ready`; a failed probe means Promtail is not ready to ship the Docker log stream. Tempo-to-Loki trace correlation maps the Tempo span resource attribute `service.name` to Loki's indexed `service_name` label, while the trace ID is matched through the structured JSON `trace.id` field. If trace navigation fails, verify those fields in the service logs and confirm that the Promtail pipeline preserved them without promoting high-cardinality identifiers to labels.

If dashboards are missing, check Grafana provisioning logs and confirm the four JSON files plus `dashboard-schema-version.json` are mounted under `/var/lib/grafana/dashboards`. If panels show no data, check Prometheus targets at <http://localhost:9090/targets>, confirm the relevant application generated the required Micrometer metric, and allow for the selected panel time range. A local `up=0` result for application targets means the example file `docker/prometheus/prometheus-scrape-token.example` is still mounted; replace it locally with a short-lived JWT carrying only `metrics.read` before expecting application metrics, and never commit that token. A no-data Cloud Armor panel is expected until managed Cloud Armor metrics are exported to Prometheus. Trace links require the same `trace.id` to be present in the structured log JSON and in Tempo; they do not use PII-bearing labels.

## Production VM Assumptions

- Machine: `e2-highmem-2` (2 vCPU, 16 GiB) – conservative `mem_limit`/`cpus` per service; do not allocate the full 16 GiB to container maxima.
- Compose `restart: unless-stopped`, `logging.max-size`/`max-file`, `security_opt: no-new-privileges:true`, `read_only: true` + `tmpfs: /tmp` for JVMs, non-root `seatflow` user, `HEALTHCHECK` on every service.
- Secrets only from runtime environment / GCP Secret Manager (never image layers); no `.env` copied into images.
- Image immutability: `image: ${AR_BASE}/<service>:${SEATFLOW_IMAGE_TAG:?required}` – tag is a Git SHA set by CI/CD (P10-007). Never use `:latest` in prod.
- TLS is intentionally not published by this Compose stack. P10-008 must introduce the tested TLS listener plus read-only certificate/renewal ownership before port 443 is exposed.
- Persistent volumes: `pg_data`, `kafka_data`, `prometheus_data`, `grafana_data`, `tempo_data`, `loki_data` survive `docker compose down` (only `down -v` drops them).
- Redis remains disposable (`--appendonly no`) and has no persistent prod volume.

## Recovery & Diagnostics

- **Health:** `docker compose ps` + `wget --spider http://localhost:<port>/actuator/health` (embedded in image `HEALTHCHECK`).
- **Logs:** `docker compose logs -f <service>` – JSON lines include correlation/trace context; Stripe/Auth secrets are masked.
- **Kafka:** `docker exec seatflow-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list`
- **PostgreSQL:** `docker exec seatflow-postgres pg_isready -U postgres`
- **Rollback:** `docker compose` keeps the previous `SEATFLOW_IMAGE_TAG` so a failed release can be reverted by re-exporting the prior tag and `docker compose up -d`.

## Notes

- The stack is intended for local development and single-VM production. Real secrets must never be committed; production deployments use GCP Secret Manager / GitHub Environments per `AGENTS.md`.
- Kafka is configured in single-node KRaft mode (`KAFKA_NODE_ID: 1`), suitable for portfolio/demo throughput, not for fault-tolerant production at scale.
- Frontend `public/env.js` holds only the Stripe publishable key (`pk_test_…`); secret keys stay in `payment-service` via `STRIPE_API_KEY`.
