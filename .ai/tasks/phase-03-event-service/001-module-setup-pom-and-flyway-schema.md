# TASK-P03-001: Event Service Module Setup, Configuration Profiles & Flyway Schema

## 1. Task Metadata
- **Task ID:** `TASK-P03-001`
- **Git Branch:** `feat/p03-001-module-setup-pom-and-flyway-schema`
- **Target Module:** `backend/services/event-service`
- **Phase:** `Phase 03 - Event Catalog Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/02-microservices-spec.md` (Section 5), `.ai/architecture/03-database-models.md` (Section 2.3), `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Bootstrap the independent `event-service` module, its secure runtime profiles, structured logging, and the complete PostgreSQL schema for the `seatflow_event` database. This task deliberately contains only module plumbing and migration/context smoke coverage; domain implementation begins in Task 002.

### Critical Invariants to Enforce:
- [ ] The service name is `event-service`, HTTP port is `8083`, and it connects only to database `seatflow_event`.
- [ ] The module inherits from `seatflow-services` and depends on all four existing common modules; do not recreate shared errors, event envelopes, exception advice, or JWT converters.
- [ ] `.env.example` contains dummy, non-secret defaults and `.env` is ignored.
- [ ] Hibernate uses `ddl-auto: validate`; Flyway is the sole owner of database DDL.
- [ ] All identifiers use `UUID DEFAULT gen_random_uuid()`, all timestamps use `TIMESTAMPTZ`, and all DDL names comply with ADR-002 prefixes.
- [ ] `events.version` is a non-null optimistic-lock column; price is `NUMERIC(10,2)` and currency is a three-character ISO code.
- [ ] The outbox has no direct Kafka coupling: unpublished events remain durable until Task 005 publishes them.
- [ ] Credentials and issuer URLs are environment-injected; no secret is committed.

---

## 3. Exact File Inventory
- `[MODIFY]` `backend/services/pom.xml` — add `event-service` to `<modules>` after `seat-map-service`.
- `[NEW]` `backend/services/event-service/pom.xml`
- `[NEW]` `backend/services/event-service/.env.example`
- `[NEW]` `backend/services/event-service/.gitignore`
- `[NEW]` `backend/services/event-service/src/main/java/com/seatflow/event/EventServiceApplication.java`
- `[NEW]` `backend/services/event-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/event-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/event-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/event-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/event-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/event-service/src/main/resources/logback-spring.xml`
- `[NEW]` `backend/services/event-service/src/main/resources/db/migration/V1__create_events_and_pricing_tables.sql`
- `[NEW]` `backend/services/event-service/src/main/resources/db/migration/V2__create_outbox_events_table.sql`
- `[NEW]` `backend/services/event-service/src/test/java/com/seatflow/event/EventServiceApplicationTests.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Parent and Service Maven Contracts
Insert exactly `<module>event-service</module>` in `backend/services/pom.xml`. The new POM must use parent `com.seatflow:seatflow-services:${project.version}`, artifact id `event-service`, and Java 21 inherited from the root. Declare these compile dependencies: `common-domain`, `common-events`, `common-observability`, `common-security`; `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-boot-starter-oauth2-resource-server`, `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-circuitbreaker-resilience4j`, `spring-kafka`, `spring-boot-starter-flyway`, `springdoc-openapi-starter-webmvc-ui`, `mapstruct`, `lombok` (provided), `micrometer-registry-prometheus`, and `logstash-logback-encoder`.

Declare `postgresql` and `flyway-database-postgresql` with runtime scope. Declare test dependencies `spring-boot-starter-test`, `spring-security-test`, `junit-jupiter`, `testcontainers`, `testcontainers-postgresql`, `spring-kafka-test`, and the Spring Boot 4 modular test starters `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-starter-security-test`, and `spring-boot-starter-jdbc-test`, all test-scoped. Configure `spring-boot-maven-plugin` to exclude Lombok from the repackaged artifact.

### 4.2 Bootstrap and Configuration Contract
`EventServiceApplication` must be in package `com.seatflow.event`, use `@SpringBootApplication(scanBasePackages = {"com.seatflow.event", "com.seatflow.common"})`, `@EnableDiscoveryClient`, and `@EnableScheduling`.

`application.yaml` must set `spring.application.name: event-service`, Jackson non-null output and ISO dates, `spring.jpa.open-in-view: false`, `hibernate.ddl-auto: validate`, JDBC timezone UTC, Flyway location `classpath:db/migration`, port `8083`, exposed actuator endpoints `health,info,prometheus,metrics`, Springdoc paths `/v3/api-docs` and `/swagger-ui.html`, and outbox defaults `fixed-delay-ms: 3000`, `batch-size: 50`, `topic: seatflow.event.events`.

`application-local.yaml` uses `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:seatflow_event}`, local Kafka/Eureka defaults, a dummy JWT issuer fallback, ten Hikari connections, and DEBUG application/SQL logging. `application-docker.yaml` uses `postgres:5432`, `kafka:9092`, and `eureka-server:8761`. `application-prod.yaml` uses required `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `EUREKA_URI`, and JWT environment variables; it disables Springdoc and uses 20 maximum/5 minimum Hikari connections, Kafka `acks: all`, and three producer retries. `application-test.yaml` uses Testcontainers-provided datasource properties, disables Eureka, keeps Flyway enabled, uses a dummy test issuer, and sets the outbox delay to `60000`.

`.env.example` must contain `SPRING_PROFILES_ACTIVE=local`, `DB_HOST=localhost`, `DB_PORT=5432`, `DB_NAME=seatflow_event`, `DB_USERNAME=seatflow`, `DB_PASSWORD=seatflow_dev`, `KAFKA_BOOTSTRAP_SERVERS=localhost:9092`, `EUREKA_URI=http://localhost:8761/eureka`, `SEAT_MAP_SERVICE_URL=http://localhost:8082`, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://seatflow.ciamlogin.com/YOUR_TENANT_ID/v2.0`, and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=api://seatflow-backend`. `.gitignore` contains exactly `.env`, `target/`, and `*.log`.

`logback-spring.xml` must provide ANSI console output for `local,docker`, Logstash JSON output for `prod` including MDC keys `traceId`, `spanId`, `correlationId`, `userId`, and `serviceName`, and a WARN-level concise console appender for `test`.

### 4.3 Flyway Migration V1 — Events and Pricing
Create `V1__create_events_and_pricing_tables.sql` with this complete DDL:

```sql
CREATE TABLE events (
    id          UUID          NOT NULL DEFAULT gen_random_uuid(),
    venue_id    UUID          NOT NULL,
    title       VARCHAR(255)  NOT NULL,
    description TEXT          NOT NULL,
    category    VARCHAR(100)  NOT NULL,
    banner_url  VARCHAR(1000),
    event_date  TIMESTAMPTZ   NOT NULL,
    status      VARCHAR(30)   NOT NULL DEFAULT 'DRAFT',
    version     BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT chk_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED'))
);

CREATE INDEX idx_events_status_date ON events(status, event_date ASC);
CREATE INDEX idx_events_category_date ON events(category, event_date ASC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_events_venue_id ON events(venue_id);
CREATE INDEX idx_events_created_at ON events(created_at DESC);

CREATE TABLE event_pricing_tiers (
    id            UUID           NOT NULL DEFAULT gen_random_uuid(),
    event_id      UUID           NOT NULL,
    section_id    UUID           NOT NULL,
    category_name VARCHAR(100)   NOT NULL,
    price         NUMERIC(10, 2) NOT NULL,
    currency      VARCHAR(3)     NOT NULL DEFAULT 'USD',
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_event_pricing_tiers PRIMARY KEY (id),
    CONSTRAINT fk_pricing_events FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT uq_event_section_tier UNIQUE (event_id, section_id, category_name),
    CONSTRAINT chk_pricing_price CHECK (price >= 0.00),
    CONSTRAINT chk_pricing_currency CHECK (length(currency) = 3)
);

CREATE INDEX idx_pricing_event_id ON event_pricing_tiers(event_id);
CREATE INDEX idx_pricing_event_section ON event_pricing_tiers(event_id, section_id);
```

### 4.4 Flyway Migration V2 — Transactional Outbox
Create `V2__create_outbox_events_table.sql` with this complete DDL:

```sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id),
    CONSTRAINT chk_event_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_event_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_event_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

### 4.5 Context and Migration Smoke Test
`EventServiceApplicationTests` uses `@SpringBootTest`, `@ActiveProfiles("test")`, and a PostgreSQL 16 Testcontainers instance wired through `@DynamicPropertySource`. It must assert context startup and query Flyway’s `flyway_schema_history` to assert successful versions `1` and `2`; it must not replace PostgreSQL with H2.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p03-001-module-setup-pom-and-flyway-schema` from `develop`.
2. Add the aggregator module and create the service POM with the specified dependencies.
3. Add environment template, ignore rules, application class, all profiles, and profile-appropriate structured logging.
4. Implement V1 then V2 exactly as specified; do not let Hibernate generate schema.
5. Write the Testcontainers context/Flyway smoke test and run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/event-service -Dtest=EventServiceApplicationTests
```

- [ ] The module is discoverable through `backend/services/pom.xml`.
- [ ] Context starts against PostgreSQL 16 and Flyway applies both migrations.
- [ ] The schema has every stated constraint and index.
- [ ] No `.env` or secret is committed and no domain code is added.
- [ ] Task file is moved to `.ai/tasks/completed/phase-03-event-service/001-module-setup-pom-and-flyway-schema.md` when complete.
