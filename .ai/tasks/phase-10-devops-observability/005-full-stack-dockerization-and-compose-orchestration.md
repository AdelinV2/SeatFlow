# TASK-P10-005: Dockerize the Full Stack and Split Compose Orchestration

## 1. Task Metadata
- **Task ID:** `TASK-P10-005`
- **Git Branch:** `feat/p10-005-full-stack-docker-compose`
- **Target Module:** `docker/`, all deployable backend modules, and `frontend/`
- **Phase:** `Phase 10 - DevOps & Observability`
- **Related Specs:** `AGENTS.md`, `.ai/architecture/08-observability-and-deployment.md`, Tasks `P10-001` through `P10-004`
- **Related ADRs:** `None` — Docker Compose layout is explicitly established by the architecture.
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants

Deliver repeatable developer containers for the ten JVM deployables and Angular/Nginx SPA, organized into core, application, and monitoring Compose files. Startup must use Docker DNS and health-aware dependency ordering, while preserving profile/environment separation.

### Critical Invariants to Enforce:
- [ ] Containers use `SPRING_PROFILES_ACTIVE=docker`; host execution uses `local`; no Docker hostname leaks into local/prod configuration.
- [ ] PostgreSQL persists all business truth; Redis is non-authoritative cache/rate-limit/fan-out infrastructure; Kafka is the durable asynchronous backbone.
- [ ] Every container is non-root, has a healthcheck, receives secrets only through runtime environment variables, and has no `.env` copied into an image.
- [ ] Service-to-service HTTP uses Eureka service IDs and Spring LoadBalancer, never static service host/port URLs in Java configuration.
- [ ] Compose images, service names, ports, volumes and networks exactly match the architecture port catalog.

---

## 3. Exact File Inventory

- `[MODIFY]` `backend/services/api-gateway/Dockerfile` and `backend/services/eureka-server/Dockerfile` — replace single-stage jar copies with multi-stage non-root builds.
- `[NEW]` `backend/services/user-service/Dockerfile`, `backend/services/seat-map-service/Dockerfile`, `backend/services/event-service/Dockerfile`, `backend/services/reservation-service/Dockerfile`, `backend/services/payment-service/Dockerfile`, `backend/services/ticket-service/Dockerfile`, `backend/services/realtime-service/Dockerfile`, and `backend/services/notification-service/Dockerfile`.
- `[NEW]` `frontend/Dockerfile` and `frontend/nginx.conf` — Angular build and immutable SPA/Nginx runtime.
- `[NEW]` `.dockerignore`, `backend/.dockerignore`, and `frontend/.dockerignore`.
- `[MODIFY]` `docker/docker-compose.yml` — core Postgres/KRaft Kafka/Redis/Eureka/Gateway only.
- `[NEW]` `docker/docker-compose.services.yml` — eight business services plus frontend.
- `[NEW]` `docker/docker-compose.monitoring.yml` — Prometheus, Grafana, OTel Collector and Tempo.
- `[NEW]` `docker/otel/tempo.yaml` and `[MODIFY]` `docker/otel/otel-collector-config.yaml` — receiver/exporter/storage wiring from P10-003.
- `[MODIFY]` `docker/prometheus/prometheus.yml`, `docker/README.md`, and `.env.example`.
- `[MODIFY]` `backend/services/api-gateway/src/main/resources/application-docker.yaml`, `backend/services/eureka-server/src/main/resources/application-docker.yaml`, and each of the eight service `src/main/resources/application-docker.yaml`.
- `[MODIFY]` `frontend/src/environments/environment.ts` and `frontend/src/environments/environment.prod.ts` — runtime API/WebSocket base URL contract; do not embed secrets.

---

## 4. Technical Specifications & Contracts

### 4.1 JVM Image Contract

All ten Dockerfiles use the same stages and the module’s declared port:

```dockerfile
FROM alpine:3.21 AS otel-agent
ARG OTEL_AGENT_VERSION
ARG OTEL_AGENT_SHA512
RUN apk add --no-cache curl && curl -fsSL -o /agent.jar "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    && echo "${OTEL_AGENT_SHA512}  /agent.jar" | sha512sum -c -

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/common backend/common
COPY backend/services backend/services
RUN mvn -f backend/pom.xml -pl services/reservation-service -am package -DskipTests -B --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S seatflow && adduser -S seatflow -G seatflow
WORKDIR /app
COPY --from=build /workspace/backend/services/reservation-service/target/*.jar /app/app.jar
COPY --from=otel-agent /agent.jar /opt/opentelemetry/opentelemetry-javaagent.jar
USER seatflow
EXPOSE 8084
ENTRYPOINT ["sh","-c","exec java $JAVA_TOOL_OPTIONS -jar /app/app.jar"]
```

Parameterize only the Maven module path, artifact source and port per Dockerfile. The `otel-agent` stage is the pinned SHA-verified agent defined by P10-003. Do not use `latest` image tags.

### 4.2 Core Compose Contract

```yaml
name: seatflow
services:
  postgres:
    image: postgres:16.6-alpine
    healthcheck: {test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER"], interval: 5s, timeout: 3s, retries: 20}
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
    healthcheck: {test: ["CMD", "redis-cli", "ping"], interval: 5s, timeout: 3s, retries: 20}
  eureka-server:
    build: {context: .., dockerfile: backend/services/eureka-server/Dockerfile}
    environment: {SPRING_PROFILES_ACTIVE: docker, EUREKA_SERVER_URL: http://eureka-server:8761/eureka}
  api-gateway:
    build: {context: .., dockerfile: backend/services/api-gateway/Dockerfile}
    depends_on: {eureka-server: {condition: service_healthy}, redis: {condition: service_healthy}}
```

Use `seatflow-net` (bridge) and named `pg_data`, `kafka_data`, `prometheus_data`, `grafana_data`, and `tempo_data` volumes. The only published core ports are 5432, 9092, 6379, 8761 and 8080. `depends_on` waits for application health checks, not merely container start.

### 4.3 Services and Monitoring Compose Contract

`docker-compose.services.yml` defines service names/ports: user 8081, seat-map 8082, event 8083, reservation 8084, payment 8085, ticket 8086, realtime 8087, notification 8088, and frontend 80 mapped to host 4200. Every service has `SPRING_PROFILES_ACTIVE=docker`, `EUREKA_SERVER_URL=http://eureka-server:8761/eureka`, `KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, and its DB-specific `DB_NAME`; resource credentials resolve as `${NAME:?set NAME in .env}` for non-dummy values.

`docker-compose.monitoring.yml` adds `otel-collector` (4317/4318), `tempo` (3200), `prometheus` (9090), and `grafana` (3000), all on `seatflow-net`. Grafana depends on healthy Prometheus/Tempo. Monitoring file publishes no service actuator port; Prometheus scrapes private DNS.

### 4.4 Docker Profile Contract

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:postgres}:${DB_PORT:5432}/${DB_NAME}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_SERVER_URL:http://eureka-server:8761/eureka}
management:
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://otel-collector:4318/v1/traces}
```

Frontend Nginx sends `/api/` and `/ws/` to `http://api-gateway:8080`, returns `index.html` for SPA routes, sets `Cache-Control: no-store` for `index.html`, immutable caching for hashed assets, and forwards `X-Request-Id`/WebSocket upgrade headers without forwarding an Authorization token into logs.

### 4.5 Execution Contract

```bash
docker compose -f docker/docker-compose.yml up -d
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml up -d --build
docker compose -f docker/docker-compose.yml -f docker/docker-compose.monitoring.yml up -d
```

The README must also specify the reverse-order `down` command, `--volumes` only as an explicit data-reset action, first-run `.env` copy, health URLs, and log/trace/dashboard URLs.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Checkout `feat/p10-005-full-stack-docker-compose` after P10-001 through P10-004 are integrated; inventory each module build output.
2. Create shared build context ignores and multi-stage Dockerfiles; prove each module image builds independently.
3. Split the existing Compose file into the exact core/services/monitoring responsibilities; correct Kafka’s advertised listener to Docker DNS.
4. Make each `application-docker.yaml` use Compose DNS and environment variables; preserve the local/prod/test profile behavior.
5. Build the frontend/Nginx image and test API, WebSocket upgrade, and SPA route fallback through Gateway.
6. Add healthchecks, dependency conditions, ports, named volumes and internal scrape/trace network wiring.
7. Write the execution, diagnostics, and recoverable data-reset guide; run configuration validation before starting containers.

---

## 6. Definition of Done & Verification Command

To verify this task, run:

```bash
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml config --quiet
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml build
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml up -d
docker compose -f docker/docker-compose.yml -f docker/docker-compose.services.yml -f docker/docker-compose.monitoring.yml ps
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:4200/
```

- [ ] All ten JVM images and the frontend image build as non-root without copying `.env`/secrets.
- [ ] All Compose files validate together and every declared healthcheck becomes healthy.
- [ ] Docker profile addresses use DNS names; no Java inter-service URL is host/port hardcoded.
- [ ] Core, services and monitoring can be started independently in the documented order.
- [ ] On completion move this file to `.ai/tasks/completed/phase-10-devops-observability/005-full-stack-dockerization-and-compose-orchestration.md`.
