# TASK-P10-005: Dockerize the Full Stack and Add Production Compose Orchestration

## 1. Task Metadata
- **Task ID:** `TASK-P10-005`
- **Git Branch:** `feat/p10-005-full-stack-docker-compose`
- **Target Module:** `docker/`, all deployable backend modules, and `frontend/`
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `AGENTS.md`, `.ai/architecture/08-observability-and-deployment.md`, Tasks `P10-002` through `P10-004`
- **Related ADRs:** `.ai/decisions/ADR-008-compute-engine-single-vm-portfolio-deployment.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants

Deliver repeatable containers for the ten JVM deployables and Angular/Nginx SPA, split into core, application, and monitoring Compose files, plus a **production override for the single GCP Compute Engine VM**.

The same independently containerized architecture must work locally and in production; production changes runtime profile, immutable images, resource limits, security, persistence, logging, and restart behavior rather than introducing a separate Cloud Run deployment model.

### Critical Invariants
- [ ] Host/IDE execution uses `local`; local Compose uses `docker`; production Compose uses `prod`; automated tests use `test`.
- [ ] `prod` uses private Docker DNS (`postgres`, `kafka`, `redis`, `eureka-server`) and is not Cloud Run-specific.
- [ ] PostgreSQL remains authoritative business state; Redis remains non-authoritative; Kafka remains the durable asynchronous backbone.
- [ ] Every application container is non-root, health-checked, immutable, and contains no `.env`/secret files.
- [ ] Production exposes only the Nginx HTTPS/WSS edge; database, broker, cache, Eureka, backend and observability ports stay private.
- [ ] PostgreSQL and Kafka data survive container recreation through named persistent volumes.
- [ ] Every JVM has bounded memory appropriate for the `e2-highmem-2` 2-vCPU/16-GiB VM.
- [ ] Service-to-service HTTP continues to use Eureka + Spring Cloud LoadBalancer.
- [ ] No Kubernetes/GKE, Cloud Run, Cloud SQL, Memorystore, or managed Kafka resources are introduced by this task.

---

## 3. Exact File Inventory

### Images
- `[MODIFY]` `backend/services/api-gateway/Dockerfile` and `backend/services/eureka-server/Dockerfile` — multi-stage non-root builds.
- `[NEW]` Dockerfiles for `user-service`, `seat-map-service`, `event-service`, `reservation-service`, `payment-service`, `ticket-service`, `realtime-service`, and `notification-service`.
- `[NEW]` `frontend/Dockerfile` and `frontend/nginx.conf` — Angular production build + Nginx runtime/reverse proxy.
- `[NEW]` `.dockerignore`, `backend/.dockerignore`, and `frontend/.dockerignore`.

### Compose
- `[MODIFY]` `docker/docker-compose.yml` — PostgreSQL/KRaft Kafka/Redis/Eureka/Gateway core.
- `[NEW]` `docker/docker-compose.services.yml` — eight business services + frontend.
- `[NEW]` `docker/docker-compose.monitoring.yml` — OTel Collector + Prometheus + Grafana + Tempo.
- `[NEW]` `docker/docker-compose.prod.yml` — production-only immutable images, `prod` profile, resource limits, private/public port policy, restart/logging/security overrides.

### Observability / Configuration
- `[NEW]` `docker/otel/tempo.yaml` and `[MODIFY]` `docker/otel/otel-collector-config.yaml`.
- `[MODIFY]` `docker/prometheus/prometheus.yml`, `docker/README.md`, and `.env.example`.
- `[MODIFY]` each JVM `application-docker.yaml` and `application-prod.yaml` so both Compose profiles resolve infrastructure through environment variables/Docker DNS while retaining different security/logging/resource behavior.
- `[MODIFY]` frontend environment/runtime configuration so `/api/` and `/ws/` are same-origin through Nginx in production.

---

## 4. JVM Image Contract

All ten JVM images use reproducible multi-stage builds, non-root runtime users, pinned base images, and the OpenTelemetry Java agent from P10-003.

Representative runtime stage:

```dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S seatflow && adduser -S seatflow -G seatflow
WORKDIR /app
COPY --from=build /workspace/.../target/*.jar /app/app.jar
COPY --from=otel-agent /agent.jar /opt/opentelemetry/opentelemetry-javaagent.jar
USER seatflow
ENTRYPOINT ["sh","-c","exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
```

Do not use `latest` as a production deployment selector.

---

## 5. Local Compose Contract

`docker-compose.yml` contains core infrastructure:

```yaml
services:
  postgres:
    image: postgres:16.6-alpine
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER"]
  kafka:
    image: apache/kafka:3.9.0
    environment:
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
  redis:
    image: redis:7.4-alpine
    command: ["redis-server", "--appendonly", "no"]
  eureka-server: {}
  api-gateway: {}
```

Local developer execution may publish service/infrastructure ports for diagnostics as documented. The production override removes those public mappings.

`docker-compose.services.yml` defines `user-service` 8081 through `notification-service` 8088 plus the frontend.

`docker-compose.monitoring.yml` defines OTel Collector, Prometheus, Grafana and Tempo on `seatflow-net`.

---

## 6. Production Compose Contract

`docker-compose.prod.yml` is applied on top of the three base files:

```bash
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.services.yml \
  -f docker/docker-compose.monitoring.yml \
  -f docker/docker-compose.prod.yml \
  config
```

### 6.1 Images

Application containers use Artifact Registry variables:

```yaml
image: ${AR_BASE}/reservation-service:${SEATFLOW_IMAGE_TAG:?required}
```

`SEATFLOW_IMAGE_TAG` is an immutable Git SHA/release selector produced by P10-007.

### 6.2 Profile & DNS

Every JVM application uses:

```yaml
environment:
  SPRING_PROFILES_ACTIVE: prod
  EUREKA_SERVER_URL: http://eureka-server:8761/eureka
  KAFKA_BOOTSTRAP_SERVERS: kafka:9092
  REDIS_HOST: redis
  DB_HOST: postgres
```

`application-prod.yaml` must therefore represent production behavior, not a Cloud Run topology.

### 6.3 Public Exposure

Production publishes only the edge HTTP/HTTPS ports required by Nginx. No production host mappings for:

```text
5432 PostgreSQL
6379 Redis
9092 Kafka
8761 Eureka
8080-8088 backend services
9090 Prometheus
3000 Grafana
3200 Tempo
4317/4318 OTel
```

Administrative access to monitoring is through a controlled tunnel/admin path, not open Internet ports.

### 6.4 Persistence

Named volumes:

```text
pg_data
kafka_data
prometheus_data
grafana_data
tempo_data
```

PostgreSQL and Kafka volumes are mandatory production state. Redis remains disposable unless a later measured requirement changes that.

### 6.5 Resource Limits

Start conservatively and validate the **entire** stack on `e2-highmem-2`:

```text
Gateway/Eureka:          ~256-384 MiB JVM heap each
Most business services:  ~256-384 MiB JVM heap each
Reservation/Event:       up to ~512 MiB when measured
Kafka:                   bounded demo heap
PostgreSQL:              bounded connections/memory
Prometheus/Tempo:        short retention and bounded storage
```

Implement Compose CPU/memory limits and `JAVA_TOOL_OPTIONS` from measured full-stack startup/runtime behavior. Leave host memory headroom; do not allocate all 16 GiB to container maxima.

### 6.6 Restart / Logging / Security

Production overrides must include:

- `restart: unless-stopped` or equivalent;
- Docker log rotation (`max-size`, `max-file`);
- read-only filesystem where practical;
- `no-new-privileges:true` where compatible;
- non-root application containers;
- health checks;
- secrets only from deployment/runtime environment, never image layers;
- private `seatflow-net` service communication.

---

## 7. Nginx / Frontend Contract

The frontend runtime is also the public edge for the single-VM deployment.

It must:

- serve Angular static assets;
- SPA fallback to `index.html`;
- proxy `/api/` to `http://api-gateway:8080`;
- proxy WebSocket/STOMP endpoint(s) with `Upgrade`/`Connection` headers;
- preserve/generate request correlation headers according to the application policy;
- apply HTTPS/security headers according to P10-008 deployment runbook;
- avoid logging Authorization headers/tokens;
- cache hashed static assets while keeping `index.html` non-stale.

---

## 8. Production Data Initialization / Migrations

Compose does not run destructive migrations automatically from every service in parallel.

P10-007 owns the release sequence. It must execute a controlled migration step before replacing the running application image set. Migrations are forward-only and backwards compatible.

The PostgreSQL container hosts separate logical databases/users for service ownership even though they share one server instance.

---

## 9. Execution Contract

### Local

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml up -d --build
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml up -d
```

### Production validation

```bash
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.services.yml \
  -f docker/docker-compose.monitoring.yml \
  -f docker/docker-compose.prod.yml \
  --env-file /run/seatflow/runtime.env \
  config --quiet
```

Production deployment itself is executed by P10-007 after immutable images are in Artifact Registry.

---

## 10. Step-by-Step Implementation Sequence

1. Build all ten JVM images and frontend image independently as non-root.
2. Finalize core/services/monitoring Compose split and local health checks.
3. Align `application-docker.yaml` with local Compose DNS.
4. Align `application-prod.yaml` with production Compose DNS plus strict production behavior; remove Cloud Run/Cloud SQL/Memorystore assumptions.
5. Add `docker-compose.prod.yml` with Artifact Registry images, immutable tag variable, resource/restart/logging/security overrides, persistence and private port policy.
6. Configure Nginx for Angular + API + WebSocket single-origin routing.
7. Validate full-stack resource usage locally and document initial VM sizing assumptions.
8. Document local and production Compose commands, recovery, volume reset warnings and diagnostics.

---

## 11. Definition of Done & Verification

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml config --quiet

docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml build

SEATFLOW_IMAGE_TAG=test \
AR_BASE=example.invalid/seatflow \
docker compose \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.services.yml \
  -f docker/docker-compose.monitoring.yml \
  -f docker/docker-compose.prod.yml \
  config --quiet
```

- [ ] All ten JVM images and frontend image build independently and non-root.
- [ ] Local Compose stack becomes healthy.
- [ ] Production Compose renders without Cloud Run/Cloud SQL/Memorystore/managed-Kafka dependencies.
- [ ] Production exposes only the edge ports.
- [ ] PostgreSQL/Kafka persistent volumes are present.
- [ ] Production uses `SPRING_PROFILES_ACTIVE=prod` and Docker DNS.
- [ ] Resource limits fit the target 2-vCPU/16-GiB VM with headroom.
- [ ] No `.env`, credentials, service-account keys, or secrets are copied into images.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/005-full-stack-dockerization-and-compose-orchestration.md`.
