# TASK-002: Common Domain Module (Exceptions, ErrorCode, DTO Envelopes)

## 1. Task Metadata
- **Target Module:** `backend/common/common-domain`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `backend/AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Implement the `common-domain` shared library providing standardized exception types, the global `ErrorCode` catalog, the unified API error envelope (`ApiErrorResponse`), validation error descriptors (`ValidationError`), and generic pagination wrappers (`PagedResult<T>`).

### Critical Invariants to Enforce:
- [ ] Immutable Java Records for all data envelopes (`ApiErrorResponse`, `ValidationError`, `PagedResult<T>`).
- [ ] No mutable fields or public constructors altering internal state.
- [ ] `BusinessException` must be unchecked (`RuntimeException`) and carry both `ErrorCode` and an HTTP status code.
- [ ] Standardized `ErrorCode` enum covering all platform domains (not found, conflicts, limits, auth, validation, internal).
- [ ] Clean module dependencies: Only `jakarta.validation-api` and standard Java 21 / Jackson annotations if needed (no heavy Spring dependencies).

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `backend/common/common-domain/pom.xml`
- `[NEW]` `backend/common/common-domain/src/main/java/com/seatflow/common/domain/enums/ErrorCode.java`
- `[NEW]` `backend/common/common-domain/src/main/java/com/seatflow/common/domain/dto/ValidationError.java`
- `[NEW]` `backend/common/common-domain/src/main/java/com/seatflow/common/domain/dto/ApiErrorResponse.java`
- `[NEW]` `backend/common/common-domain/src/main/java/com/seatflow/common/domain/dto/PagedResult.java`
- `[NEW]` `backend/common/common-domain/src/main/java/com/seatflow/common/domain/exception/BusinessException.java`
- `[NEW]` `backend/common/common-domain/src/main/java/com/seatflow/common/domain/exception/ResourceNotFoundException.java`
- `[NEW]` `backend/common/common-domain/src/main/java/com/seatflow/common/domain/exception/ConflictException.java`
- `[NEW]` `backend/common/common-domain/src/main/java/com/seatflow/common/domain/exception/ValidationException.java`
- `[NEW]` `backend/common/common-domain/src/test/java/com/seatflow/common/domain/dto/PagedResultTest.java`
- `[NEW]` `backend/common/common-domain/src/test/java/com/seatflow/common/domain/exception/ExceptionHierarchyTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Maven POM (`backend/common/common-domain/pom.xml`)
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

    <artifactId>common-domain</artifactId>
    <name>SeatFlow :: Common Domain</name>
    <description>Shared domain DTOs, ErrorCodes, and Exception hierarchy</description>

    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 4.2 Error Code Enum (`com.seatflow.common.domain.enums.ErrorCode`)
```java
package com.seatflow.common.domain.enums;

public enum ErrorCode {
    RESOURCE_NOT_FOUND("SF_404_NOT_FOUND"),
    SEAT_ALREADY_RESERVED("SF_409_SEAT_HELD"),
    RESERVATION_EXPIRED("SF_410_RESERVATION_EXPIRED"),
    RESERVATION_LIMIT_EXCEEDED("SF_400_RESERVATION_LIMIT_EXCEEDED"),
    MAX_SEATS_EXCEEDED("SF_400_MAX_SEATS_EXCEEDED"),
    PAYMENT_FAILED("SF_402_PAYMENT_FAILED"),
    PAYMENT_ALREADY_PROCESSED("SF_409_PAYMENT_PROCESSED"),
    UNAUTHORIZED("SF_401_UNAUTHORIZED"),
    FORBIDDEN("SF_403_FORBIDDEN"),
    INVALID_REQUEST("SF_400_INVALID_REQUEST"),
    CONFLICT("SF_409_CONFLICT"),
    INTERNAL_SERVER_ERROR("SF_500_INTERNAL_ERROR");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
```

### 4.3 DTO Records
```java
package com.seatflow.common.domain.dto;

public record ValidationError(
    String field,
    String message,
    Object rejectedValue
) {}
```

```java
package com.seatflow.common.domain.dto;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String errorCode,
    String message,
    String path,
    String correlationId,
    List<ValidationError> validationErrors
) {
    public static ApiErrorResponse of(int status, String error, String errorCode, String message, String path, String correlationId) {
        return new ApiErrorResponse(Instant.now(), status, error, errorCode, message, path, correlationId, List.of());
    }

    public static ApiErrorResponse withValidation(int status, String error, String errorCode, String message, String path, String correlationId, List<ValidationError> errors) {
        return new ApiErrorResponse(Instant.now(), status, error, errorCode, message, path, correlationId, errors != null ? errors : List.of());
    }
}
```

```java
package com.seatflow.common.domain.dto;

import java.util.List;

public record PagedResult<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean isFirst,
    boolean isLast
) {
    public static <T> PagedResult<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) totalElements / (double) size);
        return new PagedResult<>(
            content != null ? List.copyOf(content) : List.of(),
            page,
            size,
            totalElements,
            totalPages,
            page == 0,
            page >= totalPages - 1
        );
    }
}
```

### 4.4 Exception Hierarchy
```java
package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final int httpStatus;

    public BusinessException(String message, ErrorCode errorCode, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public BusinessException(String message, Throwable cause, ErrorCode errorCode, int httpStatus) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
```

```java
package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super(message, ErrorCode.RESOURCE_NOT_FOUND, 404);
    }

    public ResourceNotFoundException(String resourceName, Object identifier) {
        super(String.format("%s not found with identifier: %s", resourceName, identifier), ErrorCode.RESOURCE_NOT_FOUND, 404);
    }
}
```

```java
package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;

public class ConflictException extends BusinessException {
    public ConflictException(String message, ErrorCode errorCode) {
        super(message, errorCode, 409);
    }

    public ConflictException(String message) {
        super(message, ErrorCode.CONFLICT, 409);
    }
}
```

```java
package com.seatflow.common.domain.exception;

import com.seatflow.common.domain.enums.ErrorCode;

public class ValidationException extends BusinessException {
    public ValidationException(String message, ErrorCode errorCode) {
        super(message, errorCode, 400);
    }

    public ValidationException(String message) {
        super(message, ErrorCode.INVALID_REQUEST, 400);
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `backend/common/common-domain/pom.xml`.
2. **Step 2:** Implement `ErrorCode` enum with all standard platform error codes.
3. **Step 3:** Implement `ValidationError`, `ApiErrorResponse`, and `PagedResult<T>` records.
4. **Step 4:** Implement `BusinessException` and subclasses (`ResourceNotFoundException`, `ConflictException`, `ValidationException`).
5. **Step 5:** Write unit tests for `PagedResult` (pagination math, boundary cases) and `ExceptionHierarchyTest` (verifying status codes and ErrorCode mappings).
6. **Step 6:** Run test suite and verify clean compilation.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl common/common-domain
```
- [ ] All records are immutable and serialization-ready.
- [ ] All exception subclasses properly inherit status codes and ErrorCode values.
- [ ] Unit tests pass with 100% coverage on domain utility records.
- [ ] Task file is moved to `.ai/tasks/completed/`.
