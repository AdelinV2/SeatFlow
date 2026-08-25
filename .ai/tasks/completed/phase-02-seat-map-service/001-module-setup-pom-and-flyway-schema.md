# TASK-P02-001: Seat Map Service Module Setup, Configuration Profiles & Flyway Schema

## 1. Task Metadata
- **Task ID:** `TASK-P02-001`
- **Git Branch:** `feat/p02-001-module-setup-pom-and-flyway-schema`
- **Target Module:** `backend/services/seat-map-service`
- **Phase:** `Phase 02 - Seat Map & Venue Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/02-microservices-spec.md` (Section 4), `.ai/architecture/03-database-models.md` (Section 2.2), `.ai/architecture/05-messaging-and-outbox.md`
- **Related ADRs:** `.ai/decisions/ADR-002-database-indexing-and-integrity-standards.md`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Bootstrap the `seat-map-service` microservice module including Maven POM (inheriting from `seatflow-services`), Spring Boot application class, all configuration profiles (`application.yaml`, `application-local.yaml`, `application-docker.yaml`, `application-prod.yaml`, `application-test.yaml`), logging configuration, `.env.example`, and Flyway database migrations for the `venues`, `venue_sections`, `seats`, and `outbox_events` tables in the `seatflow_seatmap` database.

### Critical Invariants to Enforce:
- [ ] Service runs on port `8082` with database `seatflow_seatmap`.
- [ ] Module inherits from `seatflow-services` parent POM and declares dependencies on `common-domain`, `common-events`, `common-observability`, `common-security`.
- [ ] `.env.example` provides dummy defaults for all environment variables; `.env` is local-only and never committed.
- [ ] Flyway migrations use `TIMESTAMPTZ` for all timestamps and `gen_random_uuid()` for UUID primary keys.
- [ ] `venues` table includes `version BIGINT NOT NULL DEFAULT 0` for optimistic locking and `UNIQUE (name, city)` constraint.
- [ ] `venue_sections` table uses `CASCADE` on venue deletion and `UNIQUE (venue_id, name)`.
- [ ] `seats` table enforces two unique constraints: `(section_id, row_label, seat_number)` and `(section_id, grid_x, grid_y)` with check constraints on coordinates.
- [ ] `outbox_events` table includes `CONSTRAINT chk_seatmap_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)` and partial index on `created_at WHERE published_at IS NULL`.
- [ ] All DDL objects follow strict naming conventions per ADR-002 (`pk_`, `fk_`, `uq_`, `idx_`, `chk_` prefixes).
- [ ] No secrets hardcoded — all credentials injected via environment variables.

---

## 3. Exact File Inventory

- `[MODIFY]` `backend/services/pom.xml` — Add `<module>seat-map-service</module>`
- `[NEW]` `backend/services/seat-map-service/pom.xml`
- `[NEW]` `backend/services/seat-map-service/.env.example`
- `[NEW]` `backend/services/seat-map-service/.gitignore`
- `[NEW]` `backend/services/seat-map-service/src/main/java/com/seatflow/seatmap/SeatMapServiceApplication.java`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/logback-spring.xml`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/db/migration/V1__create_venues_and_sections_tables.sql`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/db/migration/V2__create_seats_table.sql`
- `[NEW]` `backend/services/seat-map-service/src/main/resources/db/migration/V3__create_outbox_events_table.sql`

---

## 4. Technical Specifications & Contracts

### 4.1 Services Parent POM Modification (`backend/services/pom.xml`)
Add the `seat-map-service` module to the existing `<modules>` block:
```xml
<modules>
    <module>eureka-server</module>
    <module>api-gateway</module>
    <module>user-service</module>
    <module>seat-map-service</module>
</modules>
```

### 4.2 Seat Map Service POM (`backend/services/seat-map-service/pom.xml`)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.seatflow</groupId>
        <artifactId>seatflow-services</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>seat-map-service</artifactId>
    <name>SeatFlow :: Seat Map Service</name>
    <description>Venue and Seat Map Management Microservice</description>

    <dependencies>
        <!-- SeatFlow Common Modules -->
        <dependency>
            <groupId>com.seatflow</groupId>
            <artifactId>common-domain</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.seatflow</groupId>
            <artifactId>common-events</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.seatflow</groupId>
            <artifactId>common-observability</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.seatflow</groupId>
            <artifactId>common-security</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
        </dependency>

        <!-- Spring Cloud Eureka Client -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- Kafka -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- Database -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- Spring Boot 4.x requires the Flyway starter for auto-configuration -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- OpenAPI / Swagger -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>

        <!-- MapStruct -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Observability -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- Spring Boot 4 modular test starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.3 Environment Variables (`.env.example`)
```properties
# ====================================================================
# seat-map-service Environment Variables
# Copy this file to .env and update values for local development.
# .env is strictly .gitignored — never commit real credentials.
# ====================================================================

# Spring Profile
SPRING_PROFILES_ACTIVE=local

# PostgreSQL
DB_HOST=localhost
DB_PORT=5432
DB_NAME=seatflow_seatmap
DB_USERNAME=seatflow
DB_PASSWORD=seatflow_dev

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# Eureka
EUREKA_URI=http://localhost:8761/eureka

# OAuth2 / JWT
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://seatflow.ciamlogin.com/YOUR_TENANT_ID/v2.0
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=api://seatflow-backend
```

### 4.4 `.gitignore` (`backend/services/seat-map-service/.gitignore`)
```gitignore
.env
target/
*.log
```

### 4.5 Spring Boot Application Class
```java
package com.seatflow.seatmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.seatflow.seatmap", "com.seatflow.common"})
@EnableDiscoveryClient
@EnableScheduling
public class SeatMapServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeatMapServiceApplication.class, args);
    }
}
```

### 4.6 Configuration Profiles

#### `application.yaml` (Base)
```yaml
spring:
  application:
    name: seat-map-service
  jackson:
    default-property-inclusion: non_null
    serialization:
      write-dates-as-timestamps: false
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
  port: 8082

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
```

#### `application-local.yaml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:seatflow_seatmap}
    username: ${DB_USERNAME:seatflow}
    password: ${DB_PASSWORD:seatflow_dev}
    hikari:
      maximum-pool-size: 10
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI:https://seatflow.ciamlogin.com/dummy/v2.0}
          audiences: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES:api://seatflow-backend}

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URI:http://localhost:8761/eureka}
  instance:
    prefer-ip-address: true

logging:
  level:
    com.seatflow: DEBUG
    org.hibernate.SQL: DEBUG
```

#### `application-docker.yaml`
```yaml
spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/${DB_NAME:seatflow_seatmap}
    username: ${DB_USERNAME:seatflow}
    password: ${DB_PASSWORD:seatflow_dev}
    hikari:
      maximum-pool-size: 10
  kafka:
    bootstrap-servers: kafka:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI}
          audiences: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES:api://seatflow-backend}

eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka
  instance:
    prefer-ip-address: true

logging:
  level:
    com.seatflow: INFO
```

#### `application-prod.yaml`
```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      retries: 3
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI}
          audiences: ${SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES}

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URI}
  instance:
    prefer-ip-address: true

springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false

logging:
  level:
    com.seatflow: INFO
    org.hibernate.SQL: WARN
```

#### `application-test.yaml`
```yaml
spring:
  datasource:
    url: jdbc:tc:postgresql:16-alpine:///seatflow_seatmap_test
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
  kafka:
    bootstrap-servers: ${spring.embedded.kafka.brokers:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://test-issuer.example.com

eureka:
  client:
    enabled: false
    register-with-eureka: false
    fetch-registry: false

outbox:
  publisher:
    fixed-delay-ms: 60000
    batch-size: 10

logging:
  level:
    com.seatflow: WARN
    org.hibernate.SQL: WARN
```

### 4.7 Logback Configuration (`logback-spring.xml`)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <springProfile name="local,docker">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) [%thread] %cyan(%logger{36}) - %msg %mdc%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="prod">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>spanId</includeMdcKeyName>
                <includeMdcKeyName>correlationId</includeMdcKeyName>
                <includeMdcKeyName>userId</includeMdcKeyName>
                <includeMdcKeyName>serviceName</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>

    <springProfile name="test">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="WARN">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

### 4.8 Flyway Migration V1 — Venues & Sections Tables (`V1__create_venues_and_sections_tables.sql`)
```sql
-- ====================================================================
-- V1: Create venues and venue_sections tables for seat-map-service
-- Database: seatflow_seatmap
-- Spec: .ai/architecture/03-database-models.md (Section 2.2)
-- ADR: ADR-002 (DDL naming conventions, indexing standards)
-- ====================================================================

CREATE TABLE venues (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    address     VARCHAR(500) NOT NULL,
    city        VARCHAR(100) NOT NULL,
    country     VARCHAR(100) NOT NULL DEFAULT 'USA',
    capacity    INT          NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_venues PRIMARY KEY (id),
    CONSTRAINT uq_venues_name_city UNIQUE (name, city),
    CONSTRAINT chk_venues_capacity CHECK (capacity > 0)
);

CREATE INDEX idx_venues_city ON venues(city);
CREATE INDEX idx_venues_name ON venues(name);

CREATE TABLE venue_sections (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    venue_id    UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    row_count   INT          NOT NULL,
    col_count   INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_venue_sections PRIMARY KEY (id),
    CONSTRAINT fk_venue_sections_venues FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE,
    CONSTRAINT uq_venue_sections_venue_name UNIQUE (venue_id, name),
    CONSTRAINT chk_venue_sections_row_count CHECK (row_count > 0),
    CONSTRAINT chk_venue_sections_col_count CHECK (col_count > 0)
);

CREATE INDEX idx_venue_sections_venue_id ON venue_sections(venue_id);
```

### 4.9 Flyway Migration V2 — Seats Table (`V2__create_seats_table.sql`)
```sql
-- ====================================================================
-- V2: Create seats table for seat-map-service
-- Database: seatflow_seatmap
-- Spec: .ai/architecture/03-database-models.md (Section 2.2)
-- ADR: ADR-002 (grid coordinate constraints, spatial collision prevention)
-- ====================================================================

CREATE TABLE seats (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    section_id  UUID         NOT NULL,
    row_label   VARCHAR(10)  NOT NULL,
    seat_number INT          NOT NULL,
    grid_x      INT          NOT NULL,
    grid_y      INT          NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_seats PRIMARY KEY (id),
    CONSTRAINT fk_seats_venue_sections FOREIGN KEY (section_id) REFERENCES venue_sections(id) ON DELETE CASCADE,
    CONSTRAINT uq_seats_section_row_seat UNIQUE (section_id, row_label, seat_number),
    CONSTRAINT uq_seats_section_grid UNIQUE (section_id, grid_x, grid_y),
    CONSTRAINT chk_seats_seat_number CHECK (seat_number > 0),
    CONSTRAINT chk_seats_grid_x CHECK (grid_x >= 0),
    CONSTRAINT chk_seats_grid_y CHECK (grid_y >= 0)
);

CREATE INDEX idx_seats_section_id ON seats(section_id);
-- Partial index for active seat layout queries
CREATE INDEX idx_seats_section_active ON seats(section_id) WHERE is_active = TRUE;
```

### 4.10 Flyway Migration V3 — Outbox Events Table (`V3__create_outbox_events_table.sql`)
```sql
-- ====================================================================
-- V3: Create outbox_events table for Transactional Outbox Pattern
-- Database: seatflow_seatmap
-- Spec: .ai/architecture/05-messaging-and-outbox.md (Section 3)
-- ADR: ADR-002 (outbox polling index)
-- ====================================================================

CREATE TABLE outbox_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id  UUID         NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    retry_count   INT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_seatmap_outbox PRIMARY KEY (id),
    CONSTRAINT chk_seatmap_outbox_retry CHECK (retry_count >= 0 AND retry_count <= 5)
);

-- Partial index for high-throughput outbox publisher polling
CREATE INDEX idx_seatmap_outbox_unpub ON outbox_events(created_at ASC) WHERE published_at IS NULL;
CREATE INDEX idx_seatmap_outbox_aggregate ON outbox_events(aggregate_id, created_at DESC);
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1 — Branch Checkout:** `git checkout -b feat/p02-001-module-setup-pom-and-flyway-schema develop`
2. **Step 2 — Create Module Directory:** Create `backend/services/seat-map-service/` directory structure with `src/main/java/com/seatflow/seatmap/` and `src/main/resources/`.
3. **Step 3 — Services Parent POM:** Add `<module>seat-map-service</module>` to `backend/services/pom.xml`.
4. **Step 4 — Seat Map Service POM:** Create `backend/services/seat-map-service/pom.xml` with all dependencies exactly as specified in Section 4.2.
5. **Step 5 — Environment Files:** Create `.env.example` and `.gitignore`.
6. **Step 6 — Application Class:** Create `SeatMapServiceApplication.java` with `@SpringBootApplication(scanBasePackages = {"com.seatflow.seatmap", "com.seatflow.common"})`, `@EnableDiscoveryClient`, `@EnableScheduling`.
7. **Step 7 — Configuration Profiles:** Create all 5 YAML configuration files exactly as specified in Section 4.6.
8. **Step 8 — Logback:** Create `logback-spring.xml` with profile-specific appenders.
9. **Step 9 — Flyway V1:** Create `V1__create_venues_and_sections_tables.sql` with venues and venue_sections schemas.
10. **Step 10 — Flyway V2:** Create `V2__create_seats_table.sql` with seat grid schema including unique constraints and partial index.
11. **Step 11 — Flyway V3:** Create `V3__create_outbox_events_table.sql` with outbox schema and polling index.
12. **Step 12 — Verify Compilation:** Run `mvn clean compile -pl services/seat-map-service -am` from `backend/`.

---

## 6. Definition of Done & Verification Command
To verify this task, run from the `backend/` directory:
```bash
mvn clean compile -pl services/seat-map-service -am
```
- [ ] Module compiles cleanly with zero warnings.
- [ ] `backend/services/pom.xml` includes `seat-map-service` module.
- [ ] All 5 configuration profiles are present and syntactically valid.
- [ ] `.env.example` documents all required environment variables with `seatflow_seatmap` database.
- [ ] Flyway migrations `V1`, `V2`, and `V3` are syntactically valid SQL matching `.ai/architecture/03-database-models.md` (Section 2.2).
- [ ] All DDL objects use correct naming prefixes per ADR-002.
- [ ] Task file is moved to `.ai/tasks/completed/phase-02-seat-map-service/001-module-setup-pom-and-flyway-schema.md`.
