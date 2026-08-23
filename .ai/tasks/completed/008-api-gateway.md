# TASK-008: Reactive API Gateway (Spring Cloud Gateway, Routing, CORS, Trace Relay)

## 1. Task Metadata
- **Target Module:** `backend/services/api-gateway`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/00-system-overview.md`, `.ai/architecture/02-microservices-spec.md`, `.ai/architecture/04-authentication-security.md`, `backend/AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the reactive `api-gateway` on port `8080` using Spring Cloud Gateway. Configure dynamic Eureka service discovery load balancing (`lb://<SERVICE-NAME>`), global CORS handling for the Angular frontend, distributed `X-Correlation-Id` header generation and relay, and WebSocket upgrade forwarding.

### Critical Invariants to Enforce:
- [ ] Operates on port `8080` as the sole public HTTP/WebSocket entry point.
- [ ] Reactive WebFlux architecture (must NOT include `spring-boot-starter-web` / Servlet APIs).
- [ ] Downstream routing must use Eureka discovery URIs (`lb://user-service`, `lb://seat-map-service`, `lb://event-service`, `lb://reservation-service`, `lb://payment-service`, `lb://ticket-service`, `lb://realtime-service`, `lb://notification-service`).
- [ ] Global CORS filter configured for Angular SPA origin (`http://localhost:4200` in dev).
- [ ] `GlobalFilter` ensures `X-Correlation-Id` is present on every proxied request and returned in downstream client responses.
- [ ] No database dependencies or domain business logic in gateway.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `backend/services/api-gateway/pom.xml`
- `[NEW]` `backend/services/api-gateway/src/main/java/com/seatflow/gateway/ApiGatewayApplication.java`
- `[NEW]` `backend/services/api-gateway/src/main/java/com/seatflow/gateway/config/CorsConfig.java`
- `[NEW]` `backend/services/api-gateway/src/main/java/com/seatflow/gateway/filter/CorrelationIdGlobalFilter.java`
- `[NEW]` `backend/services/api-gateway/src/main/resources/application.yaml`
- `[NEW]` `backend/services/api-gateway/src/main/resources/application-dev.yaml`
- `[NEW]` `backend/services/api-gateway/.env.example`
- `[NEW]` `backend/services/api-gateway/Dockerfile`
- `[NEW]` `backend/services/api-gateway/src/test/java/com/seatflow/gateway/ApiGatewayApplicationTests.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Maven POM (`backend/services/api-gateway/pom.xml`)
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

    <artifactId>api-gateway</artifactId>
    <name>SeatFlow :: API Gateway</name>
    <description>Reactive API Gateway with Eureka dynamic routing and CORS</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
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

### 4.2 Application Entry Point (`com.seatflow.gateway.ApiGatewayApplication`)
```java
package com.seatflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

### 4.3 Correlation ID Global Filter (`com.seatflow.gateway.filter.CorrelationIdGlobalFilter`)
```java
package com.seatflow.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);

        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, correlationId)
                .build();

        String finalCorrelationId = correlationId;
        exchange.getResponse().getHeaders().add(CORRELATION_ID_HEADER, finalCorrelationId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

### 4.4 CORS Configuration (`com.seatflow.gateway.config.CorsConfig`)
```java
package com.seatflow.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${seatflow.cors.allowed-origins:http://localhost:4200}")
    private List<String> allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Authorization"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
```

### 4.5 Configuration Files

`src/main/resources/application.yaml`:
```yaml
server:
  port: ${SERVER_PORT:8080}

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: user-service
          uri: lb://user-service
          predicates:
            - Path=/api/users/**, /api/admin/users/**

        - id: seat-map-service
          uri: lb://seat-map-service
          predicates:
            - Path=/api/venues/**, /api/admin/venues/**

        - id: event-service
          uri: lb://event-service
          predicates:
            - Path=/api/events/**, /api/admin/events/**

        - id: reservation-service
          uri: lb://reservation-service
          predicates:
            - Path=/api/reservations/**, /api/admin/reservations/**

        - id: payment-service
          uri: lb://payment-service
          predicates:
            - Path=/api/payments/**

        - id: ticket-service
          uri: lb://ticket-service
          predicates:
            - Path=/api/tickets/**, /api/admin/tickets/**

        - id: realtime-service
          uri: lb://realtime-service
          predicates:
            - Path=/ws/**

        - id: notification-service
          uri: lb://notification-service
          predicates:
            - Path=/api/notifications/**

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_SERVER_URL:http://localhost:8761/eureka/}
    register-with-eureka: true
    fetch-registry: true
  instance:
    prefer-ip-address: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,gateway
```

`backend/services/api-gateway/.env.example`:
```properties
SERVER_PORT=8080
EUREKA_SERVER_URL=http://localhost:8761/eureka/
ALLOWED_ORIGINS=http://localhost:4200
```

### 4.6 Dockerfile
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `backend/services/api-gateway/pom.xml` with `spring-cloud-starter-gateway-server-webflux` and Eureka client.
2. **Step 2:** Implement `ApiGatewayApplication` with `@SpringBootApplication` and `@EnableDiscoveryClient`.
3. **Step 3:** Implement `CorrelationIdGlobalFilter` to intercept and inject correlation headers.
4. **Step 4:** Implement `CorsConfig` to allow browser cross-origin calls from Angular SPA.
5. **Step 5:** Create `application.yaml` with explicit routes for all microservices.
6. **Step 6:** Create `.env.example` and `Dockerfile`.
7. **Step 7:** Write `ApiGatewayApplicationTests` testing Spring WebFlux context loading.
8. **Step 8:** Run test suite and verify clean build.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl services/api-gateway
```
- [ ] Reactive Spring Cloud Gateway application context starts cleanly.
- [ ] Dynamic Eureka service routes and CORS filters are properly registered.
- [ ] Task file is moved to `.ai/tasks/completed/`.
