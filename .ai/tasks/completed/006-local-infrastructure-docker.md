# TASK-006: Local Infrastructure & Observability Stack Setup (PostgreSQL, Kafka, Redis, Prometheus, Grafana)

## 1. Task Metadata
- **Target Module:** `docker/`, `.env.example`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/00-system-overview.md`, `.ai/architecture/08-observability-and-deployment.md`, `backend/AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Configure the local containerized developer infrastructure using Docker Compose. Provision:
1. Multi-database PostgreSQL 16 instance with automated initialization for all 7 business services.
2. Apache Kafka in KRaft mode (KRaft eliminates ZooKeeper) on port `9092`.
3. Redis 7 for caching and rate limiting on port `6379`.
4. Prometheus on port `9090` configured to scrape Actuator metrics across all SeatFlow microservices.
5. Grafana on port `3000` with pre-provisioned Prometheus datasource and 4 core production dashboards (Executive KPIs, Microservices RED/SRE Health, Kafka/Outbox Pipeline, Security & Auth Audit).

### Critical Invariants to Enforce:
- [ ] Database-per-service isolation: PostgreSQL must automatically create 7 distinct databases (`seatflow_user`, `seatflow_seatmap`, `seatflow_event`, `seatflow_reservation`, `seatflow_payment`, `seatflow_ticket`, `seatflow_notification`) via an init SQL script mounted in `/docker-entrypoint-initdb.d/`.
- [ ] Apache Kafka in standalone KRaft mode on port `9092` with internal and host listener configuration.
- [ ] Redis on port `6379`.
- [ ] Prometheus configuration (`prometheus.yml`) scraping microservices at `/actuator/prometheus`.
- [ ] Grafana auto-provisioned with 4 dashboards in `docker/grafana/dashboards/`:
  - `01-seatflow-executive-and-business.json`
  - `02-microservices-sre-and-red-health.json`
  - `03-kafka-and-outbox-pipeline.json`
  - `04-security-and-auth-audit.json`
- [ ] All containers must define proper `healthcheck` configurations.
- [ ] Root `.env.example` updated with Prometheus and Grafana defaults.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `docker/docker-compose.yml`
- `[NEW]` `docker/init-db/01-init-multiple-dbs.sql`
- `[NEW]` `docker/prometheus/prometheus.yml`
- `[NEW]` `docker/grafana/provisioning/datasources/datasource.yml`
- `[NEW]` `docker/grafana/provisioning/dashboards/dashboard-provider.yml`
- `[NEW]` `docker/grafana/dashboards/01-seatflow-executive-and-business.json`
- `[NEW]` `docker/grafana/dashboards/02-microservices-sre-and-red-health.json`
- `[NEW]` `docker/grafana/dashboards/03-kafka-and-outbox-pipeline.json`
- `[NEW]` `docker/grafana/dashboards/04-security-and-auth-audit.json`
- `[NEW]` `.env.example` (Root project environment template)
- `[NEW]` `docker/README.md` (Local infrastructure operating guide)

---

## 4. Technical Specifications & Contracts

### 4.1 PostgreSQL Initialization Script (`docker/init-db/01-init-multiple-dbs.sql`)
```sql
-- Initialization script for SeatFlow multi-database local development

SELECT 'CREATE DATABASE seatflow_user'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'seatflow_user')\gexec

SELECT 'CREATE DATABASE seatflow_seatmap'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'seatflow_seatmap')\gexec

SELECT 'CREATE DATABASE seatflow_event'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'seatflow_event')\gexec

SELECT 'CREATE DATABASE seatflow_reservation'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'seatflow_reservation')\gexec

SELECT 'CREATE DATABASE seatflow_payment'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'seatflow_payment')\gexec

SELECT 'CREATE DATABASE seatflow_ticket'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'seatflow_ticket')\gexec

SELECT 'CREATE DATABASE seatflow_notification'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'seatflow_notification')\gexec
```

### 4.2 Prometheus Scraping Config (`docker/prometheus/prometheus.yml`)
```yaml
global:
  scrape_interval: 5s
  evaluation_interval: 5s

scrape_configs:
  - job_name: 'seatflow-services'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'host.docker.internal:8080' # Gateway
          - 'host.docker.internal:8081' # User Service
          - 'host.docker.internal:8082' # Seat Map Service
          - 'host.docker.internal:8083' # Event Service
          - 'host.docker.internal:8084' # Reservation Service
          - 'host.docker.internal:8085' # Payment Service
          - 'host.docker.internal:8086' # Ticket Service
          - 'host.docker.internal:8087' # Realtime Service
          - 'host.docker.internal:8088' # Notification Service
```

### 4.3 Grafana Datasource & Provisioning
`docker/grafana/provisioning/datasources/datasource.yml`:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

`docker/grafana/provisioning/dashboards/dashboard-provider.yml`:
```yaml
apiVersion: 1
providers:
  - name: 'SeatFlow Dashboards'
    orgId: 1
    folder: 'SeatFlow Production'
    type: file
    disableDeletion: false
    editable: true
    options:
      path: /etc/grafana/provisioning/dashboards/json
```

### 4.4 Docker Compose Specification (`docker/docker-compose.yml`)
```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: seatflow-postgres
    restart: unless-stopped
    ports:
      - "${POSTGRES_PORT:-5432}:5432"
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-postgres}
      POSTGRES_DB: seatflow_user
    volumes:
      - pg_data:/var/lib/postgresql/data
      - ./init-db:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-postgres}"]
      interval: 5s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: seatflow-redis
    restart: unless-stopped
    ports:
      - "${REDIS_PORT:-6379}:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  kafka:
    image: apache/kafka:3.7.0
    container_name: seatflow-kafka
    restart: unless-stopped
    ports:
      - "${KAFKA_PORT:-9092}:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_NUM_PARTITIONS: 3
    volumes:
      - kafka_data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"]
      interval: 10s
      timeout: 10s
      retries: 5

  prometheus:
    image: prom/prometheus:v2.51.0
    container_name: seatflow-prometheus
    restart: unless-stopped
    ports:
      - "${PROMETHEUS_PORT:-9090}:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus
    extra_hosts:
      - "host.docker.internal:host-gateway"

  grafana:
    image: grafana/grafana:10.4.0
    container_name: seatflow-grafana
    restart: unless-stopped
    ports:
      - "${GRAFANA_PORT:-3000}:3000"
    environment:
      - GF_SECURITY_ADMIN_USER=${GRAFANA_ADMIN_USER:-admin}
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-admin}
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - ./grafana/provisioning/datasources:/etc/grafana/provisioning/datasources:ro
      - ./grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro
      - ./grafana/dashboards:/etc/grafana/provisioning/dashboards/json:ro
      - grafana_data:/var/lib/grafana
    depends_on:
      - prometheus

volumes:
  pg_data:
    driver: local
  redis_data:
    driver: local
  kafka_data:
    driver: local
  prometheus_data:
    driver: local
  grafana_data:
    driver: local
```

### 4.5 Root Environment Template (`.env.example`)
```properties
# ==========================================
# SeatFlow Infrastructure Environment Template
# ==========================================

# PostgreSQL Configuration
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
POSTGRES_PORT=5432

# Redis Configuration
REDIS_PORT=6379

# Kafka Configuration
KAFKA_PORT=9092
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# Observability Configuration
PROMETHEUS_PORT=9090
GRAFANA_PORT=3000
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=admin

# Service Registry & Gateway Ports
EUREKA_SERVER_PORT=8761
GATEWAY_PORT=8080

# Microservices Network Ports
USER_SERVICE_PORT=8081
SEAT_MAP_SERVICE_PORT=8082
EVENT_SERVICE_PORT=8083
RESERVATION_SERVICE_PORT=8084
PAYMENT_SERVICE_PORT=8085
TICKET_SERVICE_PORT=8086
REALTIME_SERVICE_PORT=8087
NOTIFICATION_SERVICE_PORT=8088

# Azure Entra External ID (OIDC / JWT)
AZURE_ENTRA_ISSUER_URI=https://seatflow.ciamlogin.com/00000000-0000-0000-0000-000000000000/v2.0
AZURE_ENTRA_CLIENT_ID=00000000-0000-0000-0000-000000000000

# Stripe Sandbox (Test Keys)
STRIPE_API_KEY=sk_test_dummy
STRIPE_WEBHOOK_SECRET=whsec_dummy
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `docker/init-db/01-init-multiple-dbs.sql` ensuring all 7 databases are initialized.
2. **Step 2:** Create `docker/prometheus/prometheus.yml` configured to scrape Actuator endpoints from host or containers.
3. **Step 3:** Create Grafana datasource and dashboard provisioning YAMLs in `docker/grafana/provisioning/`.
4. **Step 4:** Create the 4 dashboard JSON definitions in `docker/grafana/dashboards/`:
   - `01-seatflow-executive-and-business.json`
   - `02-microservices-sre-and-red-health.json`
   - `03-kafka-and-outbox-pipeline.json`
   - `04-security-and-auth-audit.json`
5. **Step 5:** Create `docker/docker-compose.yml` linking Postgres, Redis, Kafka, Prometheus, and Grafana.
6. **Step 6:** Create root `.env.example` and `docker/README.md`.
7. **Step 7:** Run `docker compose config` to validate compose structure.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
docker compose -f docker/docker-compose.yml config
```
- [ ] Docker compose file parses without schema or variable substitution errors.
- [ ] Init SQL script contains CREATE DATABASE statements for all 7 business services.
- [ ] Prometheus configuration and Grafana provisioning definitions are valid.
- [ ] Task file is moved to `.ai/tasks/completed/`.
