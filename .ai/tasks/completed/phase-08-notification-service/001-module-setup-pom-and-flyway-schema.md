# TASK-P08-001: Module Setup, POM, Profiles, Resend Properties & Flyway Migration

## 1. Task Metadata
- **Task ID:** `TASK-P08-001`
- **Git Branch:** `feat/p08-001-notification-service`
- **Target Module:** `backend/services/notification-service`
- **Phase:** `Phase 08 - Notification Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/02-microservices-spec.md` (Section 10: Port 8088), `.ai/architecture/03-database-models.md` (Section 2.7), `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `ADR-002: Database Indexing and Integrity Standards`
- **Status:** `COMPLETED`

---

## 2. Objective & Invariants
Bootstrap the `notification-service` microservice module, configure its Maven dependencies, runtime environment profiles (`application.yaml`, `application-local.yaml`, `application-docker.yaml`, `application-prod.yaml`, `application-test.yaml`), environment variable templates (`.env.example` and `.env`), structured Logstash logging, main Spring Boot application entry point, and Flyway baseline database migration creating the `notification_logs` table with all indexes, constraints, and audit timestamps.

### Critical Invariants to Enforce:
- [x] **Service Coordinates & Port Invariant:** Service name is `notification-service` and HTTP port is strictly `8088`. Database is `seatflow_notification` (PostgreSQL 16+).
- [x] **Shared Common Modules Reuse:** Inherit from `seatflow-services` parent POM and depend on all four shared common modules (`common-domain`, `common-events`, `common-observability`, `common-security`).
- [x] **Global Exception Handler Invariant:** Never declare `@RestControllerAdvice` in this service; all HTTP exception handling is auto-configured via `common-observability`.
- [x] **Flyway DDL Standards:** Primary keys use `UUID DEFAULT gen_random_uuid()` named `pk_<table>`. Unique constraints named `uq_<table>_<col>`. Check constraints named `chk_<table>_<rule>`. Indexes named `idx_<table>_<col>`. Partial index for failed retries.
- [x] **Eureka Service Discovery:** Must register with Eureka Service Discovery via `@EnableDiscoveryClient` and `spring-cloud-starter-netflix-eureka-client`.
- [x] **Environment Isolation:** Local `.env.example` contains dummy version-controlled defaults; real `.env` is `.gitignore`d and never committed.

---

## 3. Exact File Inventory
- `[MODIFY]` `backend/services/pom.xml` — add `<module>notification-service</module>`
- `[NEW]` `backend/services/notification-service/pom.xml`
- `[NEW]` `backend/services/notification-service/.env.example`
- `[NEW]` `backend/services/notification-service/.env`
- `[NEW]` `backend/services/notification-service/.gitignore`
- `[NEW]` `backend/services/notification-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/notification-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/notification-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/notification-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/notification-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/notification-service/src/main/resources/logback-spring.xml`
- `[NEW]` `backend/services/notification-service/src/main/resources/db/migration/V1__create_notification_logs_table.sql`
- `[NEW]` `backend/services/notification-service/src/main/java/com/seatflow/notification/NotificationServiceApplication.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/NotificationServiceApplicationTests.java`
- `[NEW]` `backend/services/notification-service/src/test/java/com/seatflow/notification/migration/FlywayMigrationIntegrationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Database DDL (`V1__create_notification_logs_table.sql`)
```sql
CREATE TABLE notification_logs (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    recipient_email VARCHAR(255) NOT NULL,
    template_type   VARCHAR(100) NOT NULL,
    subject         VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(255),
    status          VARCHAR(30)  NOT NULL,
    error_message   TEXT,
    sent_at         TIMESTAMPTZ,
    retry_count     INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_notification_logs PRIMARY KEY (id),
    CONSTRAINT uq_notifications_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_notif_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_notif_retries CHECK (retry_count >= 0 AND retry_count <= 5),
    CONSTRAINT chk_notif_email CHECK (recipient_email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

CREATE INDEX idx_notif_recipient_created ON notification_logs(recipient_email, created_at DESC);
CREATE INDEX idx_notif_pending_retry ON notification_logs(created_at ASC)
    WHERE status = 'FAILED' AND retry_count < 3;
```

---

## 5. Step-by-Step Implementation Sequence
1. Update `backend/services/pom.xml` to include `notification-service`.
2. Create `backend/services/notification-service/pom.xml` with dependencies (common modules, Spring Boot starters, Eureka, LoadBalancer, Resilience4j, Kafka, Flyway, PostgreSQL, Thymeleaf, MapStruct, Lombok, Testcontainers).
3. Create `.env.example`, `.env`, and `.gitignore`.
4. Create `application.yaml` and profile-specific property files (`local`, `docker`, `prod`, `test`).
5. Create `logback-spring.xml` with MDC masking and JSON appender.
6. Create `V1__create_notification_logs_table.sql`.
7. Create `NotificationServiceApplication.java` with `@SpringBootApplication`, `@EnableDiscoveryClient`, `@EnableScheduling`, `@ConfigurationPropertiesScan`.
8. Create application context and Flyway migration integration tests.

---

## 6. Definition of Done & Verification Command
```bash
mvn clean test -pl backend/services/notification-service -Dtest=NotificationServiceApplicationTests,FlywayMigrationIntegrationTest
```
