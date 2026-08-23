# TASK-006: Local Infrastructure & Docker Compose Setup (PostgreSQL Multi-DB, Kafka KRaft, Redis)

## 1. Task Metadata
- **Target Module:** `docker/`, `.env.example`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/00-system-overview.md`, `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Configure the local containerized developer infrastructure using Docker Compose. Provision a multi-database PostgreSQL 16 instance with automated database provisioning for all 7 business microservices, Apache Kafka in KRaft mode (KRaft eliminates ZooKeeper), and Redis 7 for caching and rate limiting.

### Critical Invariants to Enforce:
- [ ] Database-per-service isolation: PostgreSQL must automatically create 7 distinct databases (`seatflow_user`, `seatflow_seatmap`, `seatflow_event`, `seatflow_reservation`, `seatflow_payment`, `seatflow_ticket`, `seatflow_notification`) via an init SQL script mounted in `/docker-entrypoint-initdb.d/`.
- [ ] Apache Kafka running in standalone KRaft mode on port `9092` with internal and host listener configuration.
- [ ] Redis running on standard port `6379`.
- [ ] All containers must define proper `healthcheck` configurations.
- [ ] Persistent storage volumes declared for PostgreSQL (`pg_data`) and Kafka (`kafka_data`).
- [ ] Root `.env.example` created with sensible local defaults.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `docker/docker-compose.yml`
- `[NEW]` `docker/init-db/01-init-multiple-dbs.sql`
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

### 4.2 Docker Compose Specification (`docker/docker-compose.yml`)
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

volumes:
  pg_data:
    driver: local
  redis_data:
    driver: local
  kafka_data:
    driver: local
```

### 4.3 Root Environment Template (`.env.example`)
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
1. **Step 1:** Create `docker/init-db/01-init-multiple-dbs.sql` with queries ensuring all 7 service databases are created.
2. **Step 2:** Create `docker/docker-compose.yml` configuring PostgreSQL 16, Redis 7, and Apache Kafka (KRaft mode).
3. **Step 3:** Create root `.env.example` documenting all ports, database credentials, and external service placeholders.
4. **Step 4:** Create `docker/README.md` providing startup, teardown, and database inspection commands.
5. **Step 5:** Run `docker compose config` to validate compose file structure.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
docker compose -f docker/docker-compose.yml config
```
- [ ] Docker compose file parses without schema or variable substitution errors.
- [ ] Init SQL script contains CREATE DATABASE statements for all 7 business services.
- [ ] Root `.env.example` contains complete configuration reference.
- [ ] Task file is moved to `.ai/tasks/completed/`.
