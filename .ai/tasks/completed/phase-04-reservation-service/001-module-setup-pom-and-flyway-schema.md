# TASK-P04-001: Reservation Service Module Setup, Configuration Profiles & Flyway Schema

## 1. Task Metadata
- **Task ID:** `TASK-P04-001`
- **Git Branch:** `feat/p04-001-module-setup-pom-and-flyway-schema`
- **Target Module:** `backend/services/reservation-service`
- **Phase:** `Phase 04 - Reservation & Hold Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/02-microservices-spec.md` (Section 6), `.ai/architecture/03-database-models.md` (Section 2.4), `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Bootstrap the independent `reservation-service` microservice module, its runtime profiles across environments, structured logging configuration, and the authoritative PostgreSQL schema migrations for the `seatflow_reservation` database. This task establishes the module infrastructure, configuration properties, and database schema with all integrity constraints; domain and service layer implementation begins in Task 002.

### Critical Invariants to Enforce:
- [ ] The service name is `reservation-service`, HTTP port is `8084`, and it connects exclusively to database `seatflow_reservation`.
- [ ] The module inherits from `seatflow-services` parent POM and depends on all four shared common modules (`common-domain`, `common-events`, `common-observability`, `common-security`); never duplicate shared error responses, exceptions, event envelopes, or security helpers.
- [ ] `.env.example` contains dummy, version-controlled defaults; real `.env` is strictly `.gitignore`d and never committed.
- [ ] Hibernate configuration uses `ddl-auto: validate`; Flyway is the sole owner of database DDL.
- [ ] All primary keys use `UUID NOT NULL DEFAULT gen_random_uuid()`, all timestamps use `TIMESTAMPTZ`, and all constraint/index names strictly adhere to ADR-002 naming prefixes (`pk_`, `fk_`, `uq_`, `idx_`, `chk_`).
- [ ] **Invariant #1 (Max 10 Seats):** Enforced in Flyway V1 schema via `chk_res_seat_count CHECK (seat_count >= 1 AND seat_count <= 10)`.
- [ ] **Invariant #2 (15-Minute Expiration Sweeper Performance):** Flyway V1 schema includes partial index `idx_res_pending_expires_at ON reservations(expires_at ASC) WHERE status = 'PENDING'`.
- [ ] **Invariant #3 (Zero Double-Booking Guarantee):** Flyway V1 schema enforces a partial unique constraint on active holds: `uq_active_seat_hold ON seat_holds(event_id, seat_id) WHERE status IN ('HELD', 'SOLD')`.
- [ ] **ADR-001 (Guest Checkout):** `reservations.user_id` is nullable (`UUID NULL`) and `customer_email VARCHAR(255) NOT NULL` with regex check constraint `chk_res_email_format`.
- [ ] `reservations.version` is a non-null optimistic-lock column (`BIGINT NOT NULL DEFAULT 0`); monetary amounts use `NUMERIC(10,2)` with non-negative check constraints.
- [ ] Outbox schema in Flyway V2 (`seatflow_reservation`) contains retry ceiling constraint (`chk_res_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)`) and fast polling partial index (`idx_res_outbox_unpub`).

---

## 3. Exact File Inventory
- `[MODIFY]` `backend/services/pom.xml` — add `reservation-service` to `<modules>` after `event-service`.
- `[NEW]` `backend/services/reservation-service/pom.xml`
- `[NEW]` `backend/services/reservation-service/.env.example`
- `[NEW]` `backend/services/reservation-service/.gitignore`
- `[NEW]` `backend/services/reservation-service/src/main/java/com/seatflow/reservation/ReservationServiceApplication.java`
- `[NEW]` `backend/services/reservation-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/reservation-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/reservation-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/reservation-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/reservation-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/reservation-service/src/main/resources/logback-spring.xml`
- `[NEW]` `backend/services/reservation-service/src/main/resources/db/migration/V1__create_reservations_and_seat_holds_tables.sql`
- `[NEW]` `backend/services/reservation-service/src/main/resources/db/migration/V2__create_outbox_events_table.sql`
- `[NEW]` `backend/services/reservation-service/src/test/java/com/seatflow/reservation/ReservationServiceApplicationTests.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Aggregator and Service POM Contract
Add `<module>reservation-service</module>` in `backend/services/pom.xml`.

The `backend/services/reservation-service/pom.xml` must configure:
- **Parent:** `com.seatflow:seatflow-services:0.0.1-SNAPSHOT` with relative path `../pom.xml`.
- **Artifact Coordinates:** `groupId: com.seatflow`, `artifactId: reservation-service`, `version: 0.0.1-SNAPSHOT`, `packaging: jar`.
- **Compile Dependencies:**
  - `com.seatflow:common-domain`
  - `com.seatflow:common-events`
  - `com.seatflow:common-observability`
  - `com.seatflow:common-security`
  - `org.springframework.boot:spring-boot-starter-web`
  - `org.springframework.boot:spring-boot-starter-restclient`
  - `org.springframework.boot:spring-boot-starter-data-jpa`
  - `org.springframework.boot:spring-boot-starter-validation`
  - `org.springframework.boot:spring-boot-starter-actuator`
  - `org.springframework.boot:spring-boot-starter-oauth2-resource-server`
  - `org.springframework.cloud:spring-cloud-starter-netflix-eureka-client`
  - `org.springframework.cloud:spring-cloud-starter-loadbalancer`
  - `org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j`
  - `org.springframework.kafka:spring-kafka`
  - `org.springframework.boot:spring-boot-starter-flyway`
  - `org.flywaydb:flyway-database-postgresql` (runtime)
  - `org.postgresql:postgresql` (runtime)
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui`
  - `io.swagger.core.v3:swagger-annotations-jakarta:2.2.52`
  - `org.mapstruct:mapstruct`
  - `org.projectlombok:lombok` (provided scope)
  - `io.micrometer:micrometer-registry-prometheus`
  - `net.logstash.logback:logstash-logback-encoder`
- **Annotation Processors:**
  - Inherited from parent POM (`lombok`, `lombok-mapstruct-binding`, `mapstruct-processor`).
- **Test Dependencies:**
  - `org.springframework.boot:spring-boot-starter-test` (test)
  - `org.springframework.boot:spring-boot-starter-webmvc-test` (test)
  - `org.springframework.boot:spring-boot-starter-data-jpa-test` (test)
  - `org.springframework.boot:spring-boot-starter-security-test` (test)
  - `org.springframework.boot:spring-boot-starter-jdbc-test` (test)
  - `org.springframework.security:spring-security-test` (test)
  - `org.springframework.kafka:spring-kafka-test` (test)
  - `org.testcontainers:junit-jupiter` (test)
  - `org.testcontainers:postgresql` (test)
  - `org.testcontainers:kafka` (test)
- **Plugin:** `spring-boot-maven-plugin` excluding Lombok from repackaged artifact.

### 4.2 Application Bootstrap & Environment Profiles
`ReservationServiceApplication` in package `com.seatflow.reservation`:
```java
@SpringBootApplication(scanBasePackages = {"com.seatflow.reservation", "com.seatflow.common"})
@EnableDiscoveryClient
@EnableScheduling
public class ReservationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReservationServiceApplication.class, args);
    }
}
```

#### Profile Matrix:
- `application.yaml`:
  - `server.port: 8084`
  - `spring.application.name: reservation-service`
  - `spring.jpa.open-in-view: false`
  - `spring.jpa.hibernate.ddl-auto: validate`
  - `spring.flyway.enabled: true`, `spring.flyway.locations: classpath:db/migration`
  - `management.endpoints.web.exposure.include: health,info,prometheus,metrics`
  - `springdoc.api-docs.path: /v3/api-docs`, `springdoc.swagger-ui.path: /swagger-ui.html`
  - Outbox defaults: `outbox.publisher.fixed-delay-ms: 3000`, `outbox.publisher.batch-size: 50`, `outbox.publisher.topic: seatflow.reservation.events`
  - Reservation sweeper defaults: `reservation.cleanup.interval-ms: 10000`, `reservation.cleanup.batch-size: 100`, `reservation.cleanup.enabled: true`
  - Internal Event service client config: `event-service.base-url: ${EVENT_SERVICE_URL:http://localhost:8083}`
- `application-local.yaml`:
  - Connects to `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:seatflow_reservation}`
  - `DB_USERNAME: seatflow`, `DB_PASSWORD: seatflow_dev`
  - `spring.kafka.bootstrap-servers: localhost:9092`
  - `eureka.client.service-url.defaultZone: http://localhost:8761/eureka/`
  - `event-service.base-url: ${EVENT_SERVICE_URL:http://localhost:8083}`
  - Dummy JWT issuer fallback for local development.
- `application-docker.yaml`:
  - Connects to `jdbc:postgresql://postgres:5432/seatflow_reservation`
  - `spring.kafka.bootstrap-servers: kafka:9092`
  - `eureka.client.service-url.defaultZone: http://eureka-server:8761/eureka/`
  - `event-service.base-url: ${EVENT_SERVICE_URL:http://event-service:8083}`
- `application-prod.yaml`:
  - Strict environment-injected datasource (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), Kafka (`KAFKA_BOOTSTRAP_SERVERS`), Eureka (`EUREKA_URI`), and JWT Issuer.
  - Springdoc UI disabled (`springdoc.swagger-ui.enabled: false`).
  - Kafka producer with `acks: all`, `retries: 3`, `enable.idempotence: true`.
- `application-test.yaml`:
  - Dynamic Testcontainers datasource, Eureka disabled (`eureka.client.enabled: false`), Flyway enabled.
  - Schedulers disabled or lengthened (`outbox.publisher.fixed-delay-ms: 60000`, `reservation.cleanup.enabled: false`).
- `.env.example`:
  ```bash
  SPRING_PROFILES_ACTIVE=local
  DB_HOST=localhost
  DB_PORT=5432
  DB_NAME=seatflow_reservation
  DB_USERNAME=seatflow
  DB_PASSWORD=seatflow_dev
  KAFKA_BOOTSTRAP_SERVERS=localhost:9092
  EUREKA_URI=http://localhost:8761/eureka
  EVENT_SERVICE_URL=http://localhost:8083
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://seatflow.ciamlogin.com/YOUR_TENANT_ID/v2.0
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=api://seatflow-backend
  ```
- `logback-spring.xml`:
  - ANSI colored console logging for `local` and `docker`.
  - Logstash JSON structured logging for `prod` including MDC keys `traceId`, `spanId`, `correlationId`, `userId`, `serviceName`.
  - Terse WARN console appender for `test`.

---

### 4.3 Flyway Migration V1 — Reservations & Seat Holds DDL
Create `src/main/resources/db/migration/V1__create_reservations_and_seat_holds_tables.sql`:

```sql
CREATE TABLE reservations (
    id              UUID          NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID,         -- NULL for guest checkouts (ADR-001)
    customer_email  VARCHAR(255)  NOT NULL,
    customer_name   VARCHAR(255),
    event_id        UUID          NOT NULL,
    status          VARCHAR(30)   NOT NULL, -- PENDING, CONFIRMED, CANCELLED, EXPIRED
    expires_at      TIMESTAMPTZ   NOT NULL, -- 15-minute hold expiration timestamp
    idempotency_key VARCHAR(255)  NOT NULL,
    total_amount    NUMERIC(10,2) NOT NULL,
    seat_count      INT           NOT NULL DEFAULT 1,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_reservations PRIMARY KEY (id),
    CONSTRAINT uq_reservations_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_res_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_res_seat_count CHECK (seat_count >= 1 AND seat_count <= 10),
    CONSTRAINT chk_res_total_amount CHECK (total_amount >= 0.00),
    CONSTRAINT chk_res_email_format CHECK (customer_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- CRITICAL: Partial index for 15-minute hold sweeper scheduler (ADR-002)
CREATE INDEX idx_res_pending_expires_at ON reservations(expires_at ASC) WHERE status = 'PENDING';
CREATE INDEX idx_res_event_status ON reservations(event_id, status);
CREATE INDEX idx_res_user_status ON reservations(user_id, status) WHERE user_id IS NOT NULL;
CREATE INDEX idx_res_customer_email ON reservations(customer_email);
CREATE INDEX idx_res_created_at ON reservations(created_at DESC);

CREATE TABLE seat_holds (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    reservation_id UUID          NOT NULL,
    event_id       UUID          NOT NULL,
    seat_id        UUID          NOT NULL,
    status         VARCHAR(30)   NOT NULL, -- HELD, SOLD, RELEASED
    price          NUMERIC(10,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_seat_holds PRIMARY KEY (id),
    CONSTRAINT fk_seat_holds_reservations FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE,
    CONSTRAINT chk_seat_holds_status CHECK (status IN ('HELD', 'SOLD', 'RELEASED')),
    CONSTRAINT chk_seat_holds_price CHECK (price >= 0.00)
);

-- CRITICAL: Partial unique index guaranteeing Invariant #3 (Zero Double-Booking) (ADR-002)
CREATE UNIQUE INDEX uq_active_seat_hold ON seat_holds(event_id, seat_id)
    WHERE status IN ('HELD', 'SOLD');

CREATE INDEX idx_holds_reservation_id ON seat_holds(reservation_id);
CREATE INDEX idx_holds_event_seat ON seat_holds(event_id, seat_id);
CREATE INDEX idx_holds_event_status ON seat_holds(event_id, status);
```

---

### 4.4 Flyway Migration V2 — Transactional Outbox DDL
Create `src/main/resources/db/migration/V2__create_outbox_events_table.sql`:

```sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_res_outbox PRIMARY KEY (id),
    CONSTRAINT chk_res_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_res_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_res_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

---

### 4.5 Context & Flyway Smoke Test Contract
`ReservationServiceApplicationTests`:
- Uses `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Configures a static `PostgreSQLContainer<>("postgres:16-alpine")` via `@DynamicPropertySource`.
- Injects `JdbcTemplate` to query `flyway_schema_history` and asserts both migrations `1` and `2` succeeded (`installed_rank`, `state = 'SUCCESS'`).
- Asserts that all constraints (`chk_res_seat_count`, `uq_active_seat_hold`, `uq_reservations_idempotency_key`) are present in PostgreSQL catalogs.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p04-001-module-setup-pom-and-flyway-schema` from `develop`.
2. Add `<module>reservation-service</module>` to `backend/services/pom.xml`.
3. Create `backend/services/reservation-service/pom.xml` with the required parent and dependencies.
4. Add `.env.example`, `.gitignore`, `logback-spring.xml`, and the 5 application YAML profiles.
5. Create `ReservationServiceApplication.java` in package `com.seatflow.reservation`.
6. Implement `V1__create_reservations_and_seat_holds_tables.sql` and `V2__create_outbox_events_table.sql` with exact DDL and indexes.
7. Write `ReservationServiceApplicationTests.java` with Testcontainers PostgreSQL 16.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/reservation-service -Dtest=ReservationServiceApplicationTests
```

- [ ] Module is registered in `backend/services/pom.xml` and compiles cleanly.
- [ ] Context starts up against PostgreSQL 16 Testcontainer and Flyway applies migrations V1 and V2.
- [ ] Database schema enforces `chk_res_seat_count`, `uq_active_seat_hold`, `chk_res_email_format`, and partial indexes.
- [ ] No secrets or `.env` files are committed.
- [ ] Task file is moved to `.ai/tasks/completed/phase-04-reservation-service/001-module-setup-pom-and-flyway-schema.md` when complete.
