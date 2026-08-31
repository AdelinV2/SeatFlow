# SeatFlow Local Infrastructure Stack

Containerized developer infrastructure for SeatFlow, provisioned with Docker Compose.

## Components

| Service     | Image                      | Port (host) | Purpose                                                       |
|-------------|----------------------------|-------------|---------------------------------------------------------------|
| postgres    | `postgres:16-alpine`       | 5432        | Per-service PostgreSQL databases (7 logical databases).       |
| redis       | `redis:7-alpine`           | 6379        | Gateway rate-limit state and Realtime Pub/Sub fan-out.        |
| kafka       | `apache/kafka:3.7.0`       | 9092        | Event backbone in KRaft mode (no ZooKeeper).                 |
| prometheus  | `prom/prometheus:v2.51.0`  | 9090        | Scrapes Spring Boot Actuator `/actuator/prometheus`.          |
| tempo       | `grafana/tempo:2.4.1`      | 3200        | Distributed tracing backend (OTLP receiver on 4317/4318).     |
| loki        | `grafana/loki:3.0.0`       | 3100        | Centralized log aggregation engine for structured JSON logs.  |
| promtail    | `grafana/promtail:3.0.0`   | -           | Ships container stdout/stderr JSON logs to Loki.             |
| grafana     | `grafana/grafana:10.4.0`   | 3000        | Dashboards (admin / admin by default).                        |

## Prerequisites

- Docker Engine 24+ with the Compose plugin (`docker compose` v2).
- Copy the root `.env.example` to `.env` and adjust credentials if desired.

```bash
cp .env.example .env
```

## Start / Stop

```bash
# Start the full stack (detached)
docker compose -f docker/docker-compose.yml up -d

# Validate the compose configuration
docker compose -f docker/docker-compose.yml config

# Tail logs
docker compose -f docker/docker-compose.yml logs -f

# Stop and remove containers (keeps volumes)
docker compose -f docker/docker-compose.yml down

# Stop and remove containers AND volumes (hard reset, drops all data)
docker compose -f docker/docker-compose.yml down -v
```

## Database Layout

PostgreSQL auto-creates 7 databases via `docker/init-db/01-init-multiple-dbs.sql`:

`seatflow_user`, `seatflow_seatmap`, `seatflow_event`, `seatflow_reservation`,
`seatflow_payment`, `seatflow_ticket`, `seatflow_notification`.

Each microservice connects to its own database using the same `POSTGRES_USER`/`POSTGRES_PASSWORD`.

## Observability

- **Prometheus** scrapes every microservice over `host.docker.internal:<port>` at
  `/actuator/prometheus`. `host.docker.internal` is mapped to the host gateway via
  `extra_hosts`, so microservices running on the developer workstation are reachable
  from inside the Prometheus container.
- **Tempo** receives distributed traces from the OpenTelemetry Collector / Java Agent.
- **Loki** aggregates structured JSON logs shipped by **Promtail** from container stdout.
- **Grafana** auto-provisions `Prometheus`, `Tempo`, and `Loki` datasources with seamless
  Trace-to-Log (`tracesToLogsV2`) correlation and four dashboards under the `SeatFlow Production` folder:

  1. `01-seatflow-executive-and-business.json`
  2. `02-microservices-sre-and-red-health.json`
  3. `03-kafka-and-outbox-pipeline.json`
  4. `04-security-and-auth-audit.json`

Access Grafana at <http://localhost:3000> (default credentials `admin` / `admin`).

## Redis Responsibilities

- `api-gateway` uses Redis-backed token buckets to enforce distributed limits on
  reservation and payment creation endpoints. Limits are configured with
  `RATE_LIMIT_REPLENISH_RATE`, `RATE_LIMIT_BURST_CAPACITY`, and
  `RATE_LIMIT_REQUESTED_TOKENS`. When the gateway is behind a reverse proxy,
  set `RATE_LIMIT_TRUSTED_PROXY_CIDRS` to the proxy/ingress CIDRs; forwarded
  client addresses are ignored unless the immediate peer is in that allowlist.
- `realtime-service` publishes normalized seat-status updates to
  `REALTIME_REDIS_CHANNEL` (default `seatflow:realtime:seat-status`). Every healthy
  Realtime instance subscribes and performs only its local STOMP broadcast.
- Redis Pub/Sub is best-effort and non-durable. An offline WebSocket client must reload
  authoritative state through the REST APIs after reconnecting.
- Redis is never the source of truth for reservations, payments, tickets, or seat
  ownership. PostgreSQL remains authoritative, and Kafka plus the Transactional Outbox
  remains the durable event path.

## Notes

- The stack is intended for local development only. Real secrets must never be committed;
  production deployments use GCP Secret Manager / GitHub Environments per `AGENTS.md`.
- Kafka is configured in single-node KRaft mode (`KAFKA_NODE_ID: 1`), suitable for local
  development, not for production throughput or fault tolerance.
