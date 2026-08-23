# SeatFlow Local Infrastructure Stack

Containerized developer infrastructure for SeatFlow, provisioned with Docker Compose.

## Components

| Service     | Image                      | Port (host) | Purpose                                                       |
|-------------|----------------------------|-------------|---------------------------------------------------------------|
| postgres    | `postgres:16-alpine`       | 5432        | Per-service PostgreSQL databases (7 logical databases).       |
| redis       | `redis:7-alpine`           | 6379        | Cache and rate-limiting store.                                |
| kafka       | `apache/kafka:3.7.0`       | 9092        | Event backbone in KRaft mode (no ZooKeeper).                 |
| prometheus  | `prom/prometheus:v2.51.0`  | 9090        | Scrapes Spring Boot Actuator `/actuator/prometheus`.          |
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
- **Grafana** auto-provisions the `Prometheus` datasource and four dashboards under the
  `SeatFlow Production` folder:

  1. `01-seatflow-executive-and-business.json`
  2. `02-microservices-sre-and-red-health.json`
  3. `03-kafka-and-outbox-pipeline.json`
  4. `04-security-and-auth-audit.json`

Access Grafana at <http://localhost:3000> (default credentials `admin` / `admin`).

## Notes

- The stack is intended for local development only. Real secrets must never be committed;
  production deployments use GCP Secret Manager / GitHub Environments per `AGENTS.md`.
- Kafka is configured in single-node KRaft mode (`KAFKA_NODE_ID: 1`), suitable for local
  development, not for production throughput or fault tolerance.
