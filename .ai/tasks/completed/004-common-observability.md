# TASK-004: Common Observability Module (GlobalExceptionHandler, MDC Logging, JSON Logback & Micrometer)

## 1. Task Metadata
- **Target Module:** `backend/common/common-observability`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/08-observability-and-deployment.md`, `backend/AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the auto-configured `common-observability` shared library. This module provides:
1. Centralized HTTP exception handling via `@RestControllerAdvice` mapping exceptions to standard `ApiErrorResponse`.
2. Enterprise structured MDC request tracking via `MdcLoggingFilter` capturing `traceId`, `spanId`, `correlationId`, `userId`, `serviceName`, `httpMethod`, `uri`, and `clientIp`.
3. Standardized `logback-spring.xml` utilizing `logstash-logback-encoder` with automated PCI-DSS/GDPR sensitive data masking (credit cards, passwords, Stripe keys, JWTs).
4. Auto-configured Micrometer Prometheus metrics integration for RED method metrics and business KPI dimensional tags.

### Critical Invariants to Enforce:
- [ ] **No service-level `@RestControllerAdvice`:** Exception handling across all services is auto-configured exclusively by this module.
- [ ] Every API error response must strictly adhere to `ApiErrorResponse` and include `correlationId` and `timestamp`.
- [ ] Catch-all (500) exceptions must NOT leak internal stack traces or database errors to callers; return `ErrorCode.INTERNAL_SERVER_ERROR`.
- [ ] `MdcLoggingFilter` must populate all diagnostic context fields into SLF4J MDC, set `X-Correlation-Id` on HTTP response headers, and cleanly purge MDC in a `finally` block.
- [ ] Production profile must output single-line Logstash/ECS-compliant JSON logs. Dev profile provides ANSI color formatting.
- [ ] Sensitive attributes (credit cards, Stripe tokens, passwords) must be masked in log streams.
- [ ] Auto-configuration must use Spring Boot 4.x standard: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `backend/common/common-observability/pom.xml`
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/context/CorrelationContext.java`
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/filter/CorrelationIdFilter.java`
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/filter/MdcLoggingFilter.java`
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/handler/GlobalExceptionHandler.java`
- `[NEW]` `backend/common/common-observability/src/main/java/com/seatflow/common/observability/config/CommonObservabilityAutoConfiguration.java`
- `[NEW]` `backend/common/common-observability/src/main/resources/logback-spring.xml`
- `[NEW]` `backend/common/common-observability/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `[NEW]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/handler/GlobalExceptionHandlerTest.java`
- `[NEW]` `backend/common/common-observability/src/test/java/com/seatflow/common/observability/filter/MdcLoggingFilterTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Maven POM (`backend/common/common-observability/pom.xml`)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.seatflow</groupId>
        <artifactId>common</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>common-observability</artifactId>
    <name>SeatFlow :: Common Observability</name>
    <description>Auto-configured exception handling, MDC correlation propagation, JSON logging, and Micrometer metrics</description>

    <dependencies>
        <dependency>
            <groupId>com.seatflow</groupId>
            <artifactId>common-domain</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
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
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-core</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 4.2 Correlation Context & MDC Logging Filter
`CorrelationContext.java`:
```java
package com.seatflow.common.observability.context;

import java.util.Optional;

public final class CorrelationContext {
    private static final ThreadLocal<String> CURRENT_CORRELATION_ID = new ThreadLocal<>();

    private CorrelationContext() {}

    public static void setCorrelationId(String correlationId) {
        CURRENT_CORRELATION_ID.set(correlationId);
    }

    public static Optional<String> getCorrelationId() {
        return Optional.ofNullable(CURRENT_CORRELATION_ID.get());
    }

    public static void clear() {
        CURRENT_CORRELATION_ID.remove();
    }
}
```

`MdcLoggingFilter.java`:
```java
package com.seatflow.common.observability.filter;

import com.seatflow.common.observability.context.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcLoggingFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_SERVICE_NAME = "serviceName";
    public static final String MDC_HTTP_METHOD = "httpMethod";
    public static final String MDC_HTTP_URI = "uri";
    public static final String MDC_CLIENT_IP = "clientIp";
    public static final String MDC_USER_ID = "userId";

    @Value("${spring.application.name:seatflow-service}")
    private String serviceName;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        CorrelationContext.setCorrelationId(correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        MDC.put(MDC_CORRELATION_ID, correlationId);
        MDC.put(MDC_SERVICE_NAME, serviceName);
        MDC.put(MDC_HTTP_METHOD, request.getMethod());
        MDC.put(MDC_HTTP_URI, request.getRequestURI());
        MDC.put(MDC_CLIENT_IP, getClientIp(request));

        // Inject authenticated user ID if already resolved
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            MDC.put(MDC_USER_ID, auth.getName());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
            CorrelationContext.clear();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

### 4.3 Standard Logback Spring Configuration (`src/main/resources/logback-spring.xml`)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <springProfile name="dev,local,default">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%-5level) [%blue(%X{correlationId:-N/A})] [%cyan(%t)] %yellow(%logger{36}) : %msg%n</pattern>
                <charset>utf8</charset>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <springProfile name="staging,prod,production">
        <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>correlationId</includeMdcKeyName>
                <includeMdcKeyName>serviceName</includeMdcKeyName>
                <includeMdcKeyName>userId</includeMdcKeyName>
                <includeMdcKeyName>httpMethod</includeMdcKeyName>
                <includeMdcKeyName>uri</includeMdcKeyName>
                <includeMdcKeyName>clientIp</includeMdcKeyName>
                <includeMdcKeyName>traceId</includeMdcKeyName>
                <includeMdcKeyName>spanId</includeMdcKeyName>
                <customFields>{"application":"seatflow"}</customFields>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

### 4.4 Auto-Configured Global Exception Handler
```java
package com.seatflow.common.observability.handler;

import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.ValidationError;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.BusinessException;
import com.seatflow.common.observability.context.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("Business exception occurred: errorCode={}, message={}, uri={}", ex.getErrorCode(), ex.getMessage(), request.getRequestURI());
        ApiErrorResponse response = new ApiErrorResponse(
            Instant.now(),
            ex.getHttpStatus(),
            HttpStatus.valueOf(ex.getHttpStatus()).getReasonPhrase(),
            ex.getErrorCode().getCode(),
            ex.getMessage(),
            request.getRequestURI(),
            getCorrelationId(),
            List.of()
        );
        return ResponseEntity.status(ex.getHttpStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.warn("Validation error on request [{}]: {}", request.getRequestURI(), ex.getMessage());
        List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(this::mapFieldError)
            .toList();

        ApiErrorResponse response = ApiErrorResponse.withValidation(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            ErrorCode.INVALID_REQUEST.getCode(),
            "Validation failed for one or more fields",
            request.getRequestURI(),
            getCorrelationId(),
            errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        log.warn("Constraint violation on request [{}]: {}", request.getRequestURI(), ex.getMessage());
        List<ValidationError> errors = ex.getConstraintViolations().stream()
            .map(cv -> new ValidationError(cv.getPropertyPath().toString(), cv.getMessage(), cv.getInvalidValue()))
            .toList();

        ApiErrorResponse response = ApiErrorResponse.withValidation(
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            ErrorCode.INVALID_REQUEST.getCode(),
            "Constraint violation occurred",
            request.getRequestURI(),
            getCorrelationId(),
            errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on request [{}]: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse response = ApiErrorResponse.of(
            HttpStatus.FORBIDDEN.value(),
            HttpStatus.FORBIDDEN.getReasonPhrase(),
            ErrorCode.FORBIDDEN.getCode(),
            "Access denied: insufficient permissions",
            request.getRequestURI(),
            getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnhandledException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error on request [{}]", request.getRequestURI(), ex);
        ApiErrorResponse response = ApiErrorResponse.of(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
            ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
            "An unexpected internal error occurred. Please reference the correlation ID when contacting support.",
            request.getRequestURI(),
            getCorrelationId()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private ValidationError mapFieldError(FieldError fieldError) {
        return new ValidationError(
            fieldError.getField(),
            fieldError.getDefaultMessage(),
            fieldError.getRejectedValue()
        );
    }

    private String getCorrelationId() {
        return CorrelationContext.getCorrelationId().orElse("N/A");
    }
}
```

### 4.5 AutoConfiguration Registration
`CommonObservabilityAutoConfiguration.java`:
```java
package com.seatflow.common.observability.config;

import com.seatflow.common.observability.filter.MdcLoggingFilter;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonObservabilityAutoConfiguration {

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public MdcLoggingFilter mdcLoggingFilter() {
        return new MdcLoggingFilter();
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```text
com.seatflow.common.observability.config.CommonObservabilityAutoConfiguration
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `backend/common/common-observability/pom.xml` with dependencies for `common-domain`, `spring-boot-starter-web`, `logstash-logback-encoder`, and `micrometer-registry-prometheus`.
2. **Step 2:** Implement `CorrelationContext` and `MdcLoggingFilter`.
3. **Step 3:** Implement `GlobalExceptionHandler` with exhaustive exception mappings.
4. **Step 4:** Create `src/main/resources/logback-spring.xml` supporting ANSI console for dev and Logstash JSON for staging/prod.
5. **Step 5:** Create `CommonObservabilityAutoConfiguration` and register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
6. **Step 6:** Write unit/slice tests verifying:
   - `GlobalExceptionHandlerTest`: 400, 403, 404, 409, 500 mappings.
   - `MdcLoggingFilterTest`: MDC population, correlation ID generation, response header setting, and MDC cleanup.
7. **Step 7:** Run tests and verify clean build.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl common/common-observability
```
- [ ] Auto-configuration registers `GlobalExceptionHandler` and `MdcLoggingFilter` cleanly.
- [ ] Structured JSON logging and ANSI console logback configuration validated.
- [ ] All exception types map to consistent `ApiErrorResponse` payloads.
- [ ] Task file is moved to `.ai/tasks/completed/`.
