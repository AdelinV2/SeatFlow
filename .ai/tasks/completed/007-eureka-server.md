# TASK-007: Service Discovery (Eureka Server)

## 1. Task Metadata
- **Target Module:** `backend/services/eureka-server`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/00-system-overview.md`, `.ai/architecture/02-microservices-spec.md`, `backend/AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the standalone Spring Cloud Netflix Eureka Discovery Server (`eureka-server`) operating on port `8761`. This service provides dynamic service registration and discovery for all SeatFlow business microservices and the API Gateway.

### Critical Invariants to Enforce:
- [ ] Server operates on default port `8761`.
- [ ] Self-registration disabled (`eureka.client.register-with-eureka: false` and `eureka.client.fetch-registry: false`).
- [ ] Spring Boot 4.1.x / Spring Cloud 2025.1.x (Oakwood) compatibility.
- [ ] Zero database dependencies (in-memory registry).
- [ ] Actuator health endpoints enabled.
- [ ] Version-controlled `.env.example` with dummy default values.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `backend/services/eureka-server/pom.xml`
- `[NEW]` `backend/services/eureka-server/src/main/java/com/seatflow/eureka/EurekaServerApplication.java`
- `[NEW]` `backend/services/eureka-server/src/main/resources/application.yaml`
- `[NEW]` `backend/services/eureka-server/src/main/resources/application-dev.yaml`
- `[NEW]` `backend/services/eureka-server/.env.example`
- `[NEW]` `backend/services/eureka-server/Dockerfile`
- `[NEW]` `backend/services/eureka-server/src/test/java/com/seatflow/eureka/EurekaServerApplicationTests.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Maven POM (`backend/services/eureka-server/pom.xml`)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.seatflow</groupId>
        <artifactId>services</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>eureka-server</artifactId>
    <name>SeatFlow :: Eureka Server</name>
    <description>Netflix Eureka Service Discovery Server</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.2 Main Application Class (`com.seatflow.eureka.EurekaServerApplication`)
```java
package com.seatflow.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

### 4.3 Configuration Files

`src/main/resources/application.yaml`:
```yaml
server:
  port: ${SERVER_PORT:8761}

spring:
  application:
    name: eureka-server

eureka:
  instance:
    hostname: ${EUREKA_HOSTNAME:localhost}
  client:
    register-with-eureka: false
    fetch-registry: false
    service-url:
      defaultZone: http://${eureka.instance.hostname}:${server.port}/eureka/
  server:
    enable-self-preservation: true
    eviction-interval-timer-in-ms: 60000

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: when_authorized
```

`src/main/resources/application-dev.yaml`:
```yaml
eureka:
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000
```

`backend/services/eureka-server/.env.example`:
```properties
SERVER_PORT=8761
EUREKA_HOSTNAME=localhost
```

### 4.4 Dockerfile
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `backend/services/eureka-server/pom.xml`.
2. **Step 2:** Implement `EurekaServerApplication` annotated with `@SpringBootApplication` and `@EnableEurekaServer`.
3. **Step 3:** Create `application.yaml` and `application-dev.yaml` with self-registration disabled.
4. **Step 4:** Create `.env.example` and `Dockerfile`.
5. **Step 5:** Create `EurekaServerApplicationTests` testing that the Spring context loads successfully.
6. **Step 6:** Run tests and verify clean build.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl services/eureka-server
```
- [ ] Spring Boot application context loads cleanly.
- [ ] Eureka Server dashboard and endpoints initialize on port 8761.
- [ ] Task file is moved to `.ai/tasks/completed/`.
