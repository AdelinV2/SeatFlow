# TASK-P07-001: Realtime Service Module Setup, Configuration Profiles & WebSocket STOMP Configuration

## 1. Task Metadata
- **Task ID:** `TASK-P07-001`
- **Git Branch:** `feat/p07-001-module-setup-pom-and-websocket-stomp`
- **Target Module:** `backend/services/realtime-service`
- **Phase:** `Phase 07 - Realtime WebSocket Service`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/02-microservices-spec.md` (Section 9: Port 8087), `.ai/architecture/05-messaging-and-outbox.md`, `.ai/architecture/08-observability-and-deployment.md`
- **Related ADRs:** `None`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Bootstrap the independent `realtime-service` microservice module, its Maven POM dependency graph, runtime configuration profiles (`application.yaml`, `application-local.yaml`, `application-docker.yaml`, `application-prod.yaml`, `application-test.yaml`), `.env.example` template, structured Logstash logging configuration, and the Spring WebSocket STOMP message broker configuration. This service acts as a stateless, high-throughput WebSocket proxy and broker translating Kafka domain events into real-time STOMP topic broadcasts for connected Angular frontend clients.

### Critical Invariants to Enforce:
- [ ] **Service Coordinates & Port Invariant:** Service name is `realtime-service` and HTTP/WebSocket port is strictly `8087`.
- [ ] **Stateless Broker Invariant:** `realtime-service` contains no relational database or JPA persistence (no PostgreSQL / Flyway / Hibernate dependencies). State coordination is handled in memory via Spring WebSocket STOMP broker.
- [ ] **Shared Common Modules Reuse:** Must inherit from `seatflow-services` parent POM and depend on all four shared common modules (`common-domain`, `common-events`, `common-observability`, `common-security`); never duplicate shared error responses, exceptions, event envelopes, or security utilities.
- [ ] **Global Exception Handler Invariant:** Do NOT declare `@RestControllerAdvice` or custom global exception handlers in this service; all HTTP exception handling is auto-configured via `common-observability`.
- [ ] **Eureka Service Discovery:** Must register with Eureka Service Discovery via `@EnableDiscoveryClient` and `spring-cloud-starter-netflix-eureka-client`.
- [ ] **STOMP Broker Topology:**
  - WebSocket handshake endpoint: `/ws` with SockJS fallback enabled and configurable CORS allowed origin patterns.
  - Secondary direct WebSocket endpoint: `/ws` (without SockJS) to support native WebSocket clients and STOMP test runners.
  - In-memory message broker destination prefix: `/topic` (for client subscriptions such as `/topic/events/{eventId}/seats`).
  - Application destination prefix: `/app` (for client-to-server messages if needed).
- [ ] **CORS Configuration:** Allowed origins configured via `seatflow.cors.allowed-origins` with default `http://localhost:4200` for Angular dev server.
- [ ] **Environment Isolation:** Local `.env.example` contains dummy version-controlled defaults; real `.env` is `.gitignore`d and never committed.

---

## 3. Exact File Inventory
- `[MODIFY]` `backend/services/pom.xml` — add `<module>realtime-service</module>` to `<modules>` list after `ticket-service`.
- `[NEW]` `backend/services/realtime-service/pom.xml`
- `[NEW]` `backend/services/realtime-service/.env.example`
- `[NEW]` `backend/services/realtime-service/.gitignore`
- `[NEW]` `backend/services/realtime-service/src/main/resources/application.yaml`
- `[NEW]` `backend/services/realtime-service/src/main/resources/application-local.yaml`
- `[NEW]` `backend/services/realtime-service/src/main/resources/application-docker.yaml`
- `[NEW]` `backend/services/realtime-service/src/main/resources/application-prod.yaml`
- `[NEW]` `backend/services/realtime-service/src/main/resources/application-test.yaml`
- `[NEW]` `backend/services/realtime-service/src/main/resources/logback-spring.xml`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/RealtimeServiceApplication.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/config/WebSocketConfig.java`
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/RealtimeServiceApplicationTests.java`
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/config/WebSocketConfigTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Aggregator and Service POM Contract

#### Modify `backend/services/pom.xml`:
```xml
    <modules>
        <module>eureka-server</module>
        <module>api-gateway</module>
        <module>user-service</module>
        <module>seat-map-service</module>
        <module>event-service</module>
        <module>reservation-service</module>
        <module>payment-service</module>
        <module>ticket-service</module>
        <module>realtime-service</module>
    </modules>
```

#### Create `backend/services/realtime-service/pom.xml`:
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

    <artifactId>realtime-service</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>jar</packaging>
    <name>SeatFlow Realtime Service</name>
    <description>SeatFlow real-time WebSocket STOMP broadcasting service</description>

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

        <!-- Spring Boot Starter WebSocket (includes spring-messaging & spring-websocket) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>

        <!-- Spring Boot Starter WebMVC (for HTTP endpoints & Actuator) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>

        <!-- Spring Boot Actuator -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Spring Security & OAuth2 Resource Server (JWT decoding for WebSocket handshakes) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
        </dependency>

        <!-- Spring Cloud Eureka Client -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- Spring Kafka -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Structured Logging & Metrics -->
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- Test Dependencies -->
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
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
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

---

### 4.2 Environment Configuration Files

#### `backend/services/realtime-service/.env.example`:
```properties
# Server & Eureka
SERVER_PORT=8087
EUREKA_SERVER_URL=http://localhost:8761/eureka

# CORS Allowed Origins (Comma-separated)
CORS_ALLOWED_ORIGINS=http://localhost:4200

# Kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_CONSUMER_GROUP_ID=realtime-service

# Microsoft Entra ID (OIDC / JWT)
AZURE_ENTRA_ISSUER_URI=https://seatflow.ciamlogin.com/12345678-1234-1234-1234-123456789abc/v2.0
AZURE_ENTRA_CLIENT_ID=00000000-0000-0000-0000-000000000000
```

#### `backend/services/realtime-service/.gitignore`:
```gitignore
target/
*.class
.env
.idea/
*.iml
*.log
.DS_Store
```

---

### 4.3 Spring Boot Profiles (`application*.yaml`)

#### `src/main/resources/application.yaml`:
```yaml
spring:
  application:
    name: realtime-service
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:local}
  jackson:
    serialization:
      write-dates-as-timestamps: false
    time-zone: UTC
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${AZURE_ENTRA_ISSUER_URI:https://seatflow.ciamlogin.com/12345678-1234-1234-1234-123456789abc/v2.0}
          client-id: ${AZURE_ENTRA_CLIENT_ID:00000000-0000-0000-0000-000000000000}

server:
  port: ${SERVER_PORT:8087}
  shutdown: graceful

seatflow:
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:4200}

eureka:
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${random.value}
    lease-renewal-interval-in-seconds: 10
    lease-expiration-duration-in-seconds: 30
  client:
    service-url:
      defaultZone: ${EUREKA_SERVER_URL:http://localhost:8761/eureka}
    register-with-eureka: true
    fetch-registry: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when_authorized
      probes:
        enabled: true
  metrics:
    tags:
      application: ${spring.application.name}

logging:
  pattern:
    correlation: "[${spring.application.name},%X{traceId:-},%X{spanId:-}] "
```

#### `src/main/resources/application-local.yaml`:
```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: ${KAFKA_CONSUMER_GROUP_ID:realtime-service}
      auto-offset-reset: latest
      enable-auto-commit: false

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka

logging:
  level:
    root: INFO
    com.seatflow: DEBUG
    org.springframework.web.socket: DEBUG
    org.springframework.messaging: DEBUG
```

#### `src/main/resources/application-docker.yaml`:
```yaml
spring:
  kafka:
    bootstrap-servers: kafka:9092
    consumer:
      group-id: realtime-service
      auto-offset-reset: latest
      enable-auto-commit: false

eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka
```

#### `src/main/resources/application-prod.yaml`:
```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS}
    consumer:
      group-id: ${KAFKA_CONSUMER_GROUP_ID:realtime-service}
      auto-offset-reset: latest
      enable-auto-commit: false

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_SERVER_URL}

logging:
  config: classpath:logback-spring.xml
  level:
    root: INFO
    com.seatflow: INFO
    org.springframework.web.socket: INFO
    org.springframework.messaging: INFO
```

#### `src/main/resources/application-test.yaml`:
```yaml
spring:
  kafka:
    bootstrap-servers: ${spring.embedded.kafka.brokers:localhost:9092}
    consumer:
      group-id: realtime-service-test
      auto-offset-reset: earliest
      enable-auto-commit: true
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://seatflow.ciamlogin.com/test-tenant/v2.0

eureka:
  client:
    enabled: false
    register-with-eureka: false
    fetch-registry: false

seatflow:
  cors:
    allowed-origins: "*"

logging:
  level:
    root: WARN
    com.seatflow: DEBUG
```

---

### 4.4 Structured Logging Configuration (`logback-spring.xml`)

#### `src/main/resources/logback-spring.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration scan="true" scanPeriod="30 seconds">
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <springProfile name="!prod">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(%5p) %clr([${PID:- }]){magenta} %clr([%15.15t]){faint} %clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n%wEx</pattern>
                <charset>UTF-8</charset>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="prod">
        <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <customFields>{"service":"realtime-service"}</customFields>
                <includeMdcKeyName>correlationId</includeMdcKeyName>
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>spanId</includeMdcKeyName>
                <includeMdcKeyName>userId</includeMdcKeyName>
                <includeMdcKeyName>eventId</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

---

### 4.5 Java Application & WebSocket Configuration

#### `src/main/java/com/seatflow/realtime/RealtimeServiceApplication.java`:
```java
package com.seatflow.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class RealtimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeServiceApplication.class, args);
    }
}
```

#### `src/main/java/com/seatflow/realtime/config/WebSocketConfig.java`:
```java
package com.seatflow.realtime.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${seatflow.cors.allowed-origins:http://localhost:4200}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory message broker destination prefix for topic subscriptions (e.g. /topic/events/{eventId}/seats)
        registry.enableSimpleBroker("/topic");

        // Application prefix for messages routed to @MessageMapping methods
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        log.info("Registering STOMP endpoints on /ws with allowed origins: {}", Arrays.toString(origins));

        // 1. SockJS fallback endpoint for Angular client browser connections
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();

        // 2. Direct WebSocket endpoint for non-SockJS STOMP clients and testing harnesses
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins);
    }
}
```

---

### 4.6 Unit & Context Load Tests

#### `src/test/java/com/seatflow/realtime/RealtimeServiceApplicationTests.java`:
```java
package com.seatflow.realtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class RealtimeServiceApplicationTests {

    @Test
    @DisplayName("Should load Spring application context cleanly without relational database")
    void contextLoads() {
        assertTrue(true, "Application context loaded successfully");
    }
}
```

#### `src/test/java/com/seatflow/realtime/config/WebSocketConfigTest.java`:
```java
package com.seatflow.realtime.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketConfigTest {

    @Mock
    private MessageBrokerRegistry messageBrokerRegistry;

    @Mock
    private StompEndpointRegistry stompEndpointRegistry;

    @Mock
    private StompWebSocketEndpointRegistration endpointRegistration;

    @Test
    @DisplayName("Should configure message broker prefixes correctly")
    void configureMessageBroker_ConfiguresPrefixes() {
        WebSocketConfig config = new WebSocketConfig();

        config.configureMessageBroker(messageBrokerRegistry);

        verify(messageBrokerRegistry).enableSimpleBroker("/topic");
        verify(messageBrokerRegistry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    @DisplayName("Should register /ws endpoints with SockJS fallback and CORS origins")
    void registerStompEndpoints_RegistersEndpointsWithCors() {
        WebSocketConfig config = new WebSocketConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:4200, https://seatflow.com");

        when(stompEndpointRegistry.addEndpoint("/ws")).thenReturn(endpointRegistration);
        when(endpointRegistration.setAllowedOriginPatterns(any(String[].class))).thenReturn(endpointRegistration);

        config.registerStompEndpoints(stompEndpointRegistry);

        verify(stompEndpointRegistry, times(2)).addEndpoint("/ws");
        verify(endpointRegistration, times(1)).withSockJS();
        verify(endpointRegistration, times(2)).setAllowedOriginPatterns(new String[]{"http://localhost:4200", "https://seatflow.com"});
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Module Registration:** Update `backend/services/pom.xml` to include `<module>realtime-service</module>`.
2. **POM Creation:** Create `backend/services/realtime-service/pom.xml` with dependencies for WebSocket, Security OAuth2 Resource Server, Eureka Client, Kafka, and common modules.
3. **Environment & Profile Configuration:**
   - Create `.env.example` and `.gitignore`.
   - Create `src/main/resources/application.yaml`, `application-local.yaml`, `application-docker.yaml`, `application-prod.yaml`, and `application-test.yaml`.
   - Create `src/main/resources/logback-spring.xml`.
4. **Application Bootstrap:** Create `com.seatflow.realtime.RealtimeServiceApplication` with `@SpringBootApplication` and `@EnableDiscoveryClient`.
5. **WebSocket Configuration:** Create `com.seatflow.realtime.config.WebSocketConfig` implementing `WebSocketMessageBrokerConfigurer` configuring broker `/topic` and endpoint `/ws`.
6. **Testing & Verification:**
   - Create `RealtimeServiceApplicationTests.java` and `WebSocketConfigTest.java`.
   - Execute verification command and ensure all tests pass.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl backend/services/realtime-service -Dtest=RealtimeServiceApplicationTests,WebSocketConfigTest
```
- [ ] `backend/services/pom.xml` contains `<module>realtime-service</module>`.
- [ ] `realtime-service` compiles cleanly with zero warnings.
- [ ] Spring application context loads without JPA or database dependencies.
- [ ] `WebSocketConfig` correctly enables `/topic` simple broker, `/app` application prefix, and registers `/ws` endpoints with CORS patterns.
- [ ] All unit and context tests pass.
- [ ] Task file is moved to `.ai/tasks/completed/phase-07-realtime-service/001-module-setup-pom-and-websocket-stomp-configuration.md`.
