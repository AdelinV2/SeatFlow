# TASK-P05-001: Payment Service Module Setup, Configuration Profiles & Flyway Schema

## 1. Task Metadata
- **Task ID:** `TASK-P05-001`
- **Git Branch:** `feat/p05-001-module-setup-pom-and-flyway-schema`
- **Target Module:** `backend/services/payment-service`
- **Phase:** `Phase 05 - Payment & Stripe Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/02-microservices-spec.md` (Section 7), `.ai/architecture/03-database-models.md` (Section 2.5), `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `.ai/decisions/ADR-001-guest-checkout-and-ticketing-flow.md`, `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Bootstrap the independent `payment-service` microservice module, its runtime profiles across environments, structured logging configuration, Stripe SDK integration dependencies, and the authoritative PostgreSQL schema migrations for the `seatflow_payment` database. This task establishes the module infrastructure, configuration properties, and database schema with all integrity constraints; domain and service layer implementation begins in Task 002.

### Critical Invariants to Enforce:
- [ ] The service name is `payment-service`, HTTP port is `8085`, and it connects exclusively to database `seatflow_payment`.
- [ ] The module inherits from `seatflow-services` parent POM and depends on all four shared common modules (`common-domain`, `common-events`, `common-observability`, `common-security`); never duplicate shared error responses, exceptions, event envelopes, or security helpers.
- [ ] `.env.example` contains dummy, version-controlled defaults including Stripe test placeholders (`STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`); real `.env` is strictly `.gitignore`d and never committed.
- [ ] Hibernate configuration uses `ddl-auto: validate`; Flyway is the sole owner of database DDL.
- [ ] All primary keys use `UUID NOT NULL DEFAULT gen_random_uuid()`, all timestamps use `TIMESTAMPTZ`, and all constraint/index names strictly adhere to ADR-002 naming prefixes (`pk_`, `fk_`, `uq_`, `idx_`, `chk_`).
- [ ] **ADR-001 (Guest Checkout):** `payments.user_id` is nullable (`UUID NULL`) and `customer_email VARCHAR(255) NOT NULL` with regex check constraint `chk_payments_email`.
- [ ] **Single Payment Pipeline Invariant:** `payments.reservation_id` has a unique constraint `uq_payments_reservation_id` to guarantee only one payment pipeline exists per reservation.
- [ ] **Stripe Idempotency & Partial Index:** Flyway V1 schema includes partial unique index `uq_payments_stripe_intent ON payments(stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL`.
- [ ] `payments.version` is a non-null optimistic-lock column (`BIGINT NOT NULL DEFAULT 0`); monetary amounts use `NUMERIC(10,2)` with `chk_payments_amount CHECK (amount > 0.00)`.
- [ ] Outbox schema in Flyway V2 (`seatflow_payment`) contains retry ceiling constraint (`chk_pay_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)`) and fast polling partial index (`idx_pay_outbox_unpub`).

---

## 3. Exact File Inventory
- `[MODIFY]` `backend/services/pom.xml` — add `payment-service` to `<modules>` after `reservation-service`.
- `[NEW]` `backend/services/payment-service/pom.xml`
- `[NEW]` `backend/services/payment-service/.env.example`
- `[NEW]` `backend/services/payment-service/.gitignore`
- `[NEW]` `backend/services/payment-service/src/main/java/com/seatflow/payment/PaymentServiceApplication.java`
- `[NEW]` `backend/services/payment-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/payment-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/payment-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/payment-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/payment-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/payment-service/src/main/resources/logback-spring.xml`
- `[NEW]` `backend/services/payment-service/src/main/resources/db/migration/V1__create_payments_table.sql`
- `[NEW]` `backend/services/payment-service/src/main/resources/db/migration/V2__create_outbox_events_table.sql`
- `[NEW]` `backend/services/payment-service/src/test/java/com/seatflow/payment/PaymentServiceApplicationTests.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Aggregator and Service POM Contract
Add `<module>payment-service</module>` in `backend/services/pom.xml`.

The `backend/services/payment-service/pom.xml` must configure:
- **Parent:** `com.seatflow:seatflow-services:0.0.1-SNAPSHOT` with relative path `../pom.xml`.
- **Artifact Coordinates:** `groupId: com.seatflow`, `artifactId: payment-service`, `version: 0.0.1-SNAPSHOT`, `packaging: jar`.
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
  - `com.stripe:stripe-java:28.0.0`
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
`PaymentServiceApplication` in package `com.seatflow.payment`:
```java
package com.seatflow.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.seatflow.payment", "com.seatflow.common"})
@EnableDiscoveryClient
@EnableScheduling
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
```

#### Profile Matrix:
- `application.yaml`:
  ```yaml
  spring:
    application:
      name: payment-service
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
    port: 8085

  reservation-service:
    base-url: ${RESERVATION_SERVICE_URL:http://reservation-service}

  stripe:
    api-key: ${STRIPE_API_KEY:sk_test_dummy_key}
    webhook-secret: ${STRIPE_WEBHOOK_SECRET:whsec_dummy_secret}

  resilience4j:
    circuitbreaker:
      instances:
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
      topic: seatflow.payment.events
  ```
- `application-local.yaml`:
  - Connects to `jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:seatflow_payment}`
  - `DB_USERNAME: seatflow`, `DB_PASSWORD: seatflow_dev`
  - `spring.kafka.bootstrap-servers: localhost:9092`
  - `eureka.client.service-url.defaultZone: http://localhost:8761/eureka/`
  - `reservation-service.base-url: ${RESERVATION_SERVICE_URL:http://localhost:8084}`
  - Dummy JWT issuer fallback for local development.
- `application-docker.yaml`:
  - Connects to `jdbc:postgresql://postgres:5432/seatflow_payment`
  - `spring.kafka.bootstrap-servers: kafka:9092`
  - `eureka.client.service-url.defaultZone: http://eureka-server:8761/eureka/`
  - `reservation-service.base-url: ${RESERVATION_SERVICE_URL:http://reservation-service:8084}`
- `application-prod.yaml`:
  - Strict environment-injected datasource (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), Kafka (`KAFKA_BOOTSTRAP_SERVERS`), Eureka (`EUREKA_URI`), Stripe (`STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`), and JWT Issuer.
  - Springdoc UI disabled (`springdoc.swagger-ui.enabled: false`).
  - Kafka producer with `acks: all`, `retries: 3`, `enable.idempotence: true`.
- `application-test.yaml`:
  - Dynamic Testcontainers datasource, Eureka disabled (`eureka.client.enabled: false`), Flyway enabled.
  - Schedulers disabled or lengthened (`outbox.publisher.fixed-delay-ms: 60000`).
  - Stripe dummy keys (`stripe.api-key: sk_test_test`, `stripe.webhook-secret: whsec_test`).
  - Kafka bootstrap: `${spring.embedded.kafka.brokers:localhost:9092}`.
- `.env.example`:
  ```bash
  SPRING_PROFILES_ACTIVE=local
  DB_HOST=localhost
  DB_PORT=5432
  DB_NAME=seatflow_payment
  DB_USERNAME=seatflow
  DB_PASSWORD=seatflow_dev
  KAFKA_BOOTSTRAP_SERVERS=localhost:9092
  EUREKA_URI=http://localhost:8761/eureka
  RESERVATION_SERVICE_URL=http://localhost:8084
  STRIPE_API_KEY=sk_test_51MockStripeApiKey123456789
  STRIPE_WEBHOOK_SECRET=whsec_mockWebhookSecret123456789
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://seatflow.ciamlogin.com/YOUR_TENANT_ID/v2.0
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=api://seatflow-backend
  ```
- `logback-spring.xml`:
  - ANSI colored console logging for `local` and `docker`.
  - Logstash JSON structured logging for `prod` including MDC keys `traceId`, `spanId`, `correlationId`, `userId`, `serviceName`.
  - Terse WARN console appender for `test`.

---

### 4.3 Flyway Migration V1 — Payments DDL
Create `src/main/resources/db/migration/V1__create_payments_table.sql`:

```sql
CREATE TABLE payments (
    id                       UUID          NOT NULL DEFAULT gen_random_uuid(),
    reservation_id           UUID          NOT NULL,
    user_id                  UUID,         -- NULL for guest checkouts (ADR-001)
    customer_email           VARCHAR(255)  NOT NULL,
    event_id                 UUID          NOT NULL,
    stripe_payment_intent_id VARCHAR(255),
    idempotency_key          VARCHAR(255)  NOT NULL,
    amount                   NUMERIC(10,2) NOT NULL,
    currency                 VARCHAR(3)    NOT NULL DEFAULT 'USD',
    status                   VARCHAR(30)   NOT NULL, -- INITIATED, SUCCESS, FAILED, REFUNDED
    failure_reason           TEXT,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_payments_reservation_id UNIQUE (reservation_id), -- Only 1 payment pipeline per reservation
    CONSTRAINT chk_payments_status CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'REFUNDED')),
    CONSTRAINT chk_payments_amount CHECK (amount > 0.00),
    CONSTRAINT chk_payments_currency CHECK (length(currency) = 3),
    CONSTRAINT chk_payments_email CHECK (customer_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

-- Partial unique index for Stripe PaymentIntent idempotency (ADR-002)
CREATE UNIQUE INDEX uq_payments_stripe_intent ON payments(stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;

CREATE INDEX idx_payments_reservation_id ON payments(reservation_id);
CREATE INDEX idx_payments_user_id ON payments(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_payments_customer_email ON payments(customer_email);
CREATE INDEX idx_payments_status_created ON payments(status, created_at DESC);
CREATE INDEX idx_payments_event_id ON payments(event_id);
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

    CONSTRAINT pk_pay_outbox PRIMARY KEY (id),
    CONSTRAINT chk_pay_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

CREATE INDEX idx_pay_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_pay_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

---

### 4.5 Context & Flyway Smoke Test Contract
`PaymentServiceApplicationTests`:
- Uses `@SpringBootTest`, `@ActiveProfiles("test")`, `@Testcontainers`.
- Configures a static `PostgreSQLContainer<>("postgres:16-alpine")` via `@DynamicPropertySource`.
- Injects `JdbcTemplate` to query `flyway_schema_history` and asserts both migrations `1` and `2` succeeded (`installed_rank`, `state = 'SUCCESS'`).
- Asserts that all constraints (`chk_payments_status`, `chk_payments_amount`, `uq_payments_reservation_id`, `uq_payments_idempotency_key`, `uq_payments_stripe_intent`) are present in PostgreSQL catalogs.

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. Check out `feat/p05-001-module-setup-pom-and-flyway-schema` from `develop`.
2. Add `<module>payment-service</module>` to `backend/services/pom.xml`.
3. Create `backend/services/payment-service/pom.xml` with the required parent, Stripe Java SDK, and service dependencies.
4. Add `.env.example`, `.gitignore`, `logback-spring.xml`, and the 5 application YAML profiles.
5. Create `PaymentServiceApplication.java` in package `com.seatflow.payment`.
6. Implement `V1__create_payments_table.sql` and `V2__create_outbox_events_table.sql` with exact DDL and indexes.
7. Write `PaymentServiceApplicationTests.java` with Testcontainers PostgreSQL 16.
8. Run the verification command.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the repository root:

```bash
mvn clean test -pl backend/services/payment-service -Dtest=PaymentServiceApplicationTests
```

- [ ] Module is registered in `backend/services/pom.xml` and compiles cleanly.
- [ ] Context starts up against PostgreSQL 16 Testcontainer and Flyway applies migrations V1 and V2.
- [ ] Database schema enforces `uq_payments_reservation_id`, `uq_payments_stripe_intent`, `chk_payments_amount`, `chk_payments_email`, and partial indexes.
- [ ] No secrets or real `.env` files are committed.
- [ ] Task file is moved to `.ai/tasks/completed/phase-05-payment-service/001-module-setup-pom-and-flyway-schema.md` when complete.
