# TASK-P06-001: Ticket Service Module Setup, Configuration Profiles & Flyway Schema

## 1. Task Metadata
- **Task ID:** `TASK-P06-001`
- **Git Branch:** `feat/p06-001-module-setup-pom-and-flyway-schema`
- **Target Module:** `backend/services/ticket-service`
- **Phase:** `Phase 06 - Ticket & QR Code Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/02-microservices-spec.md` (Section 8: Port 8086), `.ai/architecture/03-database-models.md` (Section 2.6: `seatflow_ticket`), `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`, `.ai/decisions/ADR-004-stripe-tax-and-tax-inclusive-pricing.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Bootstrap the independent `ticket-service` microservice module, its runtime profiles across environments, structured logging configuration, ZXing QR generation and OpenPDF dependencies, and the authoritative PostgreSQL schema migrations for the `seatflow_ticket` database. This task establishes the module infrastructure, configuration properties, and database schema with all integrity constraints; domain and service layer implementation begins in Task 002.

### Critical Invariants to Enforce:
- [ ] The service name is `ticket-service`, HTTP port is `8086`, and it connects exclusively to database `seatflow_ticket`.
- [ ] The module inherits from `seatflow-services` parent POM and depends on all four shared common modules (`common-domain`, `common-events`, `common-observability`, `common-security`); never duplicate shared error responses, exceptions, event envelopes, or security helpers.
- [ ] Third-party libraries for QR code generation (`com.google.zxing:core:3.5.3`, `com.google.zxing:javase:3.5.3`) and PDF rendering (`com.github.librepdf:openpdf:2.0.3`) must be correctly declared in the POM.
- [ ] `.env.example` contains dummy, version-controlled defaults; real `.env` is strictly `.gitignore`d and never committed.
- [ ] Hibernate configuration uses `ddl-auto: validate`; Flyway is the sole owner of database DDL.
- [ ] All primary keys use `UUID NOT NULL DEFAULT gen_random_uuid()`, all timestamps use `TIMESTAMPTZ`, and all constraint/index names strictly adhere to ADR-002 naming prefixes (`pk_`, `fk_`, `uq_`, `idx_`, `chk_`).
- [ ] **ADR-001 (Guest Checkout):** `tickets.user_id` is nullable (`UUID NULL`) and `customer_email VARCHAR(255) NOT NULL` with regex check constraint `chk_tickets_email`.
- [ ] **Physical Seat Invariant (ADR-002):** Flyway V1 schema includes partial unique index `uq_tickets_event_seat_valid ON tickets(event_id, seat_id) WHERE status = 'VALID'` to ensure a seat cannot have more than one valid ticket for an event.
- [ ] **Fiscal Breakdown Invariant (ADR-004):** `tickets` table persists `price NUMERIC(10,2)` (gross tax-inclusive total), `tax_amount NUMERIC(10,2)` (computed tax portion), and `net_amount NUMERIC(10,2)` (net base price) with non-negative check constraints.
- [ ] `tickets.version` is a non-null optimistic-lock column (`BIGINT NOT NULL DEFAULT 0`).
- [ ] **Gate Scanner Audit Log (ADR-002):** `ticket_validations` table records every scan attempt with `ticket_id UUID NULL` (nullable to allow logging scans for unrecognized/invalid QR codes without Foreign Key violations) and `chk_validations_result CHECK (scan_result IN ('SUCCESS', 'ALREADY_USED', 'INVALID', 'CANCELLED'))`.
- [ ] Outbox schema in Flyway V3 (`seatflow_ticket`) contains retry ceiling constraint (`chk_ticket_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)`) and fast polling partial index (`idx_ticket_outbox_unpub`).

---

## 3. Exact File Inventory
- `[MODIFY]` `backend/services/pom.xml` — add `ticket-service` to `<modules>` after `payment-service`.
- `[NEW]` `backend/services/ticket-service/pom.xml`
- `[NEW]` `backend/services/ticket-service/.env.example`
- `[NEW]` `backend/services/ticket-service/.gitignore`
- `[NEW]` `backend/services/ticket-service/src/main/java/com/seatflow/ticket/TicketServiceApplication.java`
- `[NEW]` `backend/services/ticket-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/ticket-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/ticket-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/ticket-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/ticket-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/ticket-service/src/main/resources/logback-spring.xml`
- `[NEW]` `backend/services/ticket-service/src/main/resources/db/migration/V1__create_tickets_table.sql`
- `[NEW]` `backend/services/ticket-service/src/main/resources/db/migration/V2__create_ticket_validations_table.sql`
- `[NEW]` `backend/services/ticket-service/src/main/resources/db/migration/V3__create_outbox_events_table.sql`
- `[NEW]` `backend/services/ticket-service/src/test/java/com/seatflow/ticket/TicketServiceApplicationTests.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Aggregator and Service POM Contract
Add `<module>ticket-service</module>` in `backend/services/pom.xml`.

The `backend/services/ticket-service/pom.xml` must configure:
- **Parent:** `com.seatflow:seatflow-services:0.0.1-SNAPSHOT` with relative path `../pom.xml`.
- **Artifact Coordinates:** `groupId: com.seatflow`, `artifactId: ticket-service`, `version: 0.0.1-SNAPSHOT`, `packaging: jar`.
- **Compile Dependencies:**
  - `com.seatflow:common-domain`
  - `com.seatflow:common-events`
  - `com.seatflow:common-observability`
  - `com.seatflow:common-security`
  - `org.springframework.boot:spring-boot-starter-webmvc`
  - `org.springframework.boot:spring-boot-starter-restclient`
  - `org.springframework.boot:spring-boot-starter-data-jpa`
  - `org.springframework.boot:spring-boot-starter-validation`
  - `org.springframework.boot:spring-boot-starter-actuator`
  - `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server`
  - `org.springframework.cloud:spring-cloud-starter-netflix-eureka-client`
  - `org.springframework.cloud:spring-cloud-starter-loadbalancer`
  - `org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j`
  - `org.springframework.kafka:spring-kafka`
  - `org.springframework.boot:spring-boot-starter-flyway`
  - `org.flywaydb:flyway-database-postgresql` (runtime)
  - `org.postgresql:postgresql` (runtime)
  - `com.google.zxing:core:3.5.3`
  - `com.google.zxing:javase:3.5.3`
  - `com.github.librepdf:openpdf:2.0.3`
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
  - `org.junit.jupiter:junit-jupiter` (test)
  - `org.springframework.kafka:spring-kafka-test` (test)
  - `org.testcontainers:junit-jupiter` (test)
  - `org.testcontainers:postgresql` (test)
  - `org.testcontainers:kafka` (test)
- **Plugin:** `spring-boot-maven-plugin` excluding Lombok from repackaged artifact.

### 4.2 Application Bootstrap & Environment Profiles
`TicketServiceApplication` in package `com.seatflow.ticket`:
```java
package com.seatflow.ticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.seatflow.ticket", "com.seatflow.common"})
@EnableDiscoveryClient
@EnableScheduling
public class TicketServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TicketServiceApplication.class, args);
    }
}
```

#### Profile Matrix:
- `application.yaml`:
  ```yaml
  spring:
    application:
      name: ticket-service
    jackson:
      default-property-inclusion: non_null
      time-zone: UTC
    jpa:
      open-in-view: false
      hibernate:
        ddl-auto: validate
      properties:
        hibernate:
          jdbc:
            time_zone: UTC
    flyway:
      enabled: true
      locations: classpath:db/migration
      baseline-on-migrate: false

  server:
    port: 8086

  event-service:
    base-url: ${EVENT_SERVICE_URL:http://event-service}

  reservation-service:
    base-url: ${RESERVATION_SERVICE_URL:http://reservation-service}

  seat-map-service:
    base-url: ${SEAT_MAP_SERVICE_URL:http://seat-map-service}

  resilience4j:
    circuitbreaker:
      instances:
        eventService:
          register-health-indicator: true
          sliding-window-type: COUNT_BASED
          sliding-window-size: 10
          failure-rate-threshold: 50
          wait-duration-in-open-state: 30s
          permitted-number-of-calls-in-half-open-state: 3
          ignoreExceptions:
            - com.seatflow.common.domain.exception.ResourceNotFoundException
            - com.seatflow.common.domain.exception.ValidationException
        reservationService:
          register-health-indicator: true
          sliding-window-type: COUNT_BASED
          sliding-window-size: 10
          failure-rate-threshold: 50
          wait-duration-in-open-state: 30s
          permitted-number-of-calls-in-half-open-state: 3
          ignoreExceptions:
            - com.seatflow.common.domain.exception.ResourceNotFoundException
            - com.seatflow.common.domain.exception.ValidationException
        seatMapService:
          register-health-indicator: true
          sliding-window-type: COUNT_BASED
          sliding-window-size: 10
          failure-rate-threshold: 50
          wait-duration-in-open-state: 30s
          permitted-number-of-calls-in-half-open-state: 3
          ignoreExceptions:
            - com.seatflow.common.domain.exception.ResourceNotFoundException
            - com.seatflow.common.domain.exception.ValidationException

  eureka:
    client:
      service-url:
        defaultZone: ${EUREKA_SERVER_URL:http://localhost:8761/eureka}

  management:
    endpoints:
      web:
        exposure:
          include: health,info,prometheus,metrics
    endpoint:
      health:
        show-details: always

  springdoc:
    api-docs:
      path: /v3/api-docs
    swagger-ui:
      path: /swagger-ui.html
      operations-sorter: method

  outbox:
    publisher:
      fixed-delay-ms: 3000
      batch-size: 50
      topic: seatflow.ticket.events
  ```
- `application-local.yaml`:
  - Connects to `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:seatflow_ticket}`
  - `DB_USERNAME: seatflow`, `DB_PASSWORD: seatflow_dev`
  - `spring.kafka.bootstrap-servers: localhost:9092`
  - `eureka.client.service-url.defaultZone: http://localhost:8761/eureka/`
  - Inter-service client fallback URLs pointing to localhost: `EVENT_SERVICE_URL: http://localhost:8083`, `RESERVATION_SERVICE_URL: http://localhost:8084`, `SEAT_MAP_SERVICE_URL: http://localhost:8082`.
  - Dummy JWT issuer fallback for local development.
- `application-docker.yaml`:
  - Connects to `jdbc:postgresql://postgres:5432/seatflow_ticket`
  - `spring.kafka.bootstrap-servers: kafka:9092`
  - `eureka.client.service-url.defaultZone: http://eureka-server:8761/eureka/`
  - `event-service.base-url: ${EVENT_SERVICE_URL:http://event-service:8083}`
  - `reservation-service.base-url: ${RESERVATION_SERVICE_URL:http://reservation-service:8084}`
  - `seat-map-service.base-url: ${SEAT_MAP_SERVICE_URL:http://seat-map-service:8082}`
- `application-prod.yaml`:
  - Strict environment-injected datasource (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), Kafka (`KAFKA_BOOTSTRAP_SERVERS`), Eureka (`EUREKA_URI`), and JWT Issuer.
  - Springdoc UI disabled (`springdoc.swagger-ui.enabled: false`).
  - Kafka producer with `acks: all`, `retries: 3`, `enable.idempotence: true`.
- `application-test.yaml`:
  - Dynamic Testcontainers datasource, Eureka disabled (`eureka.client.enabled: false`), Flyway enabled.
  - Schedulers disabled or lengthened (`outbox.publisher.fixed-delay-ms: 60000`).
  - Kafka bootstrap: `${spring.embedded.kafka.brokers:localhost:9092}`.
- `.env.example`:
  ```bash
  SPRING_PROFILES_ACTIVE=local
  DB_HOST=localhost
  DB_PORT=5432
  DB_NAME=seatflow_ticket
  DB_USERNAME=seatflow
  DB_PASSWORD=seatflow_dev
  KAFKA_BOOTSTRAP_SERVERS=localhost:9092
  EUREKA_URI=http://localhost:8761/eureka
  EVENT_SERVICE_URL=http://localhost:8083
  RESERVATION_SERVICE_URL=http://localhost:8084
  SEAT_MAP_SERVICE_URL=http://localhost:8082
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://seatflow.ciamlogin.com/YOUR_TENANT_ID/v2.0
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=api://seatflow-backend
  ```
- `logback-spring.xml`:
  - ANSI colored console logging for `local` and `docker`.
  - Logstash JSON structured logging for `prod` including MDC keys `traceId`, `spanId`, `correlationId`, `userId`, `serviceName`.
  - Terse WARN console appender for `test`.

---

### 4.3 Flyway Migration V1 — Tickets DDL
Create `src/main/resources/db/migration/V1__create_tickets_table.sql`:

```sql
CREATE TABLE tickets (
    id             UUID          NOT NULL DEFAULT gen_random_uuid(),
    reservation_id UUID          NOT NULL,
    payment_id     UUID          NOT NULL,
    user_id        UUID,         -- NULL for guest checkouts (ADR-001)
    customer_email VARCHAR(255)  NOT NULL,
    attendee_name  VARCHAR(255),
    event_id       UUID          NOT NULL,
    seat_id        UUID          NOT NULL,
    price          NUMERIC(10,2) NOT NULL, -- Total gross ticket price (tax-inclusive)
    tax_amount     NUMERIC(10,2) NOT NULL DEFAULT 0.00, -- Tax/VAT portion (ADR-004)
    net_amount     NUMERIC(10,2) NOT NULL DEFAULT 0.00, -- Net base price (ADR-004)
    ticket_code    VARCHAR(64)   NOT NULL, -- Cryptographically secure token (URL-safe)
    qr_code_data   TEXT          NOT NULL,
    status         VARCHAR(30)   NOT NULL DEFAULT 'VALID', -- VALID, USED, CANCELLED
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_tickets PRIMARY KEY (id),
    CONSTRAINT uq_tickets_ticket_code UNIQUE (ticket_code),
    CONSTRAINT uq_tickets_reservation_seat UNIQUE (reservation_id, seat_id),
    CONSTRAINT chk_tickets_status CHECK (status IN ('VALID', 'USED', 'CANCELLED')),
    CONSTRAINT chk_tickets_price CHECK (price >= 0.00),
    CONSTRAINT chk_tickets_tax_amount CHECK (tax_amount >= 0.00),
    CONSTRAINT chk_tickets_net_amount CHECK (net_amount >= 0.00),
    CONSTRAINT chk_tickets_email CHECK (customer_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- Partial unique index: A physical seat can only have ONE valid ticket per event (ADR-002)
CREATE UNIQUE INDEX uq_tickets_event_seat_valid ON tickets(event_id, seat_id)
    WHERE status = 'VALID';

CREATE INDEX idx_tickets_event_status ON tickets(event_id, status);
CREATE INDEX idx_tickets_user_id ON tickets(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_tickets_customer_email ON tickets(customer_email);
CREATE INDEX idx_tickets_reservation_id ON tickets(reservation_id);
CREATE INDEX idx_tickets_payment_id ON tickets(payment_id);
```

---

### 4.4 Flyway Migration V2 — Gate Scanner Audit Log DDL
Create `src/main/resources/db/migration/V2__create_ticket_validations_table.sql`:

```sql
CREATE TABLE ticket_validations (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    ticket_id         UUID,        -- NULL for invalid/unrecognized ticket scans to prevent FK violation
    scanner_device_id VARCHAR(100) NOT NULL,
    scan_result       VARCHAR(30)  NOT NULL, -- SUCCESS, ALREADY_USED, INVALID, CANCELLED
    details           TEXT,
    scanned_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_ticket_validations PRIMARY KEY (id),
    CONSTRAINT fk_validations_tickets FOREIGN KEY (ticket_id) REFERENCES tickets(id) ON DELETE CASCADE,
    CONSTRAINT chk_validations_result CHECK (scan_result IN ('SUCCESS', 'ALREADY_USED', 'INVALID', 'CANCELLED'))
);

CREATE INDEX idx_validations_ticket_id ON ticket_validations(ticket_id, scanned_at DESC);
CREATE INDEX idx_validations_device ON ticket_validations(scanner_device_id, scanned_at DESC);
```

---

### 4.5 Flyway Migration V3 — Transactional Outbox DDL
Create `src/main/resources/db/migration/V3__create_outbox_events_table.sql`:

```sql
CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_ticket_outbox PRIMARY KEY (id),
    CONSTRAINT chk_ticket_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_ticket_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_ticket_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

---

### 4.6 Context & Flyway Smoke Test Contract
`TicketServiceApplicationTests`:
- Uses `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Configures a static `PostgreSQLContainer<>("postgres:16-alpine")` via `@DynamicPropertySource`.
- Injects `JdbcTemplate` to query `flyway_schema_history` and asserts migrations `1`, `2`, and `3` succeeded (`installed_rank`, `state = 'SUCCESS'`).
- Asserts that all constraints (`pk_tickets`, `uq_tickets_ticket_code`, `uq_tickets_reservation_seat`, `uq_tickets_event_seat_valid`, `chk_tickets_status`, `chk_tickets_price`, `chk_tickets_tax_amount`, `chk_tickets_net_amount`, `chk_tickets_email`, `pk_ticket_validations`, `fk_validations_tickets`, `chk_validations_result`, `pk_ticket_outbox`, `chk_ticket_outbox_retry`) are present in PostgreSQL catalogs.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p06-001-module-setup-pom-and-flyway-schema` from `develop`.
2. Add `<module>ticket-service</module>` to `backend/services/pom.xml`.
3. Create `backend/services/ticket-service/pom.xml` with the required parent, ZXing, OpenPDF, and service dependencies.
4. Add `.env.example`, `.gitignore`, `logback-spring.xml`, and the 5 application YAML profiles.
5. Create `TicketServiceApplication.java` in package `com.seatflow.ticket`.
6. Implement `V1__create_tickets_table.sql`, `V2__create_ticket_validations_table.sql`, and `V3__create_outbox_events_table.sql` with exact DDL and indexes.
7. Write `TicketServiceApplicationTests.java` with Testcontainers PostgreSQL 16.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/ticket-service -Dtest=TicketServiceApplicationTests
```

- [ ] Module is registered in `backend/services/pom.xml` and compiles cleanly.
- [ ] Context starts up against PostgreSQL 16 Testcontainer and Flyway applies migrations V1, V2, and V3.
- [ ] Database schema enforces `uq_tickets_ticket_code`, `uq_tickets_event_seat_valid`, `chk_tickets_price`, `chk_tickets_tax_amount`, `chk_tickets_net_amount`, `chk_tickets_email`, and partial indexes.
- [ ] `ticket_validations.ticket_id` is nullable to allow logging scans for unrecognized QR codes without FK violation.
- [ ] No secrets or real `.env` files are committed.
- [ ] Task file is moved to `.ai/tasks/completed/phase-06-ticket-service/001-module-setup-pom-and-flyway-schema.md` when complete.
