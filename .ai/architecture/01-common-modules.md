# 01 — Shared Common Modules Specification

**Location:** `backend/common/`  
**Purpose:** Reusable, cross-cutting enterprise domain primitives, event envelopes, security helpers, and auto-configured exception handlers.

---

## 1. Overview & Anti-Duplication Contract

The 4 common modules are dependencies of all SeatFlow microservices. 

> **CRITICAL RULE:** Never recreate exceptions, custom error DTOs, `@RestControllerAdvice`, or JWT role converters in business services. They are auto-configured by these shared modules.

```text
backend/common/
├── common-domain/          # Base exceptions, ErrorCode enum, ApiErrorResponse, PagedResult
├── common-events/          # EventEnvelope<T>, DomainEvent contract, EventHeaders, EventTopics
├── common-observability/   # Auto-configured GlobalExceptionHandler, CorrelationIdFilter, MDC helpers
└── common-security/        # Auto-configured JwtRoleConverter, SecurityRoles, UserContext helper
```

---

## 2. `common-domain`

Provides core domain contracts, pagination wrappers, and the standardized exception hierarchy.

### 2.1 Standard API Error Envelope (`ApiErrorResponse`)
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
) {}
```

```java
public record ValidationError(
    String field,
    String message,
    Object rejectedValue
) {}
```

### 2.2 Generic Pagination Record (`PagedResult<T>`)
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
) {}
```

### 2.3 Exception Hierarchy & Error Codes
```java
// Base unchecked exception
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final int httpStatus;
    // constructors
}

// 404 Not Found
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super(message, ErrorCode.RESOURCE_NOT_FOUND, 404);
    }
}

// 409 Conflict (e.g. Double booking, optimistic lock failure)
public class ConflictException extends BusinessException {
    public ConflictException(String message, ErrorCode errorCode) {
        super(message, errorCode, 409);
    }
}

// 400 Validation / Business Rule Violation
public class ValidationException extends BusinessException {
    public ValidationException(String message, ErrorCode errorCode) {
        super(message, errorCode, 400);
    }
}
```

Standard `ErrorCode` Enum:
`RESOURCE_NOT_FOUND`, `SEAT_ALREADY_RESERVED`, `RESERVATION_EXPIRED`, `RESERVATION_LIMIT_EXCEEDED`, `PAYMENT_FAILED`, `PAYMENT_ALREADY_PROCESSED`, `UNAUTHORIZED`, `FORBIDDEN`, `INVALID_REQUEST`, `INTERNAL_SERVER_ERROR`.

---

## 3. `common-events`

Standardizes Kafka domain event envelopes and event metadata.

### 3.1 Event Envelope Contract (`EventEnvelope<T>`)
```java
package com.seatflow.common.events;

import java.time.Instant;

public record EventEnvelope<T>(
    String eventId,          // UUID unique to this event instance (for consumer idempotency)
    String eventType,        // e.g. "ReservationHeld", "PaymentCompleted"
    Instant occurredAt,      // ISO-8601 UTC timestamp
    String correlationId,    // Originating request trace ID
    String causationId,      // ID of the upstream event or command
    String aggregateId,      // e.g. reservationId, paymentId
    int version,             // Schema version (starts at 1)
    T payload                // Strongly-typed domain payload implementing DomainEvent
) {}
```

### 3.2 Kafka Topic Constants (`EventTopics`)
```java
package com.seatflow.common.events;

public final class EventTopics {
    public static final String RESERVATION_EVENTS = "seatflow.reservation.events";
    public static final String PAYMENT_EVENTS = "seatflow.payment.events";
    public static final String TICKET_EVENTS = "seatflow.ticket.events";
    public static final String SEAT_STATUS_EVENTS = "seatflow.seat.status.events";
    public static final String NOTIFICATION_EVENTS = "seatflow.notification.events";
}
```

```java
// DomainEvent marker interface
package com.seatflow.common.events;

public interface DomainEvent {}
```

```java
// Event header constants
package com.seatflow.common.events;

public final class EventHeaders {
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String CAUSATION_ID = "X-Causation-Id";
    public static final String EVENT_ID = "X-Event-Id";
    public static final String EVENT_TYPE = "X-Event-Type";
    private EventHeaders() {}
}
```

---

## 4. `common-observability`

Auto-configures centralized exception handling, correlation context propagation, and MDC filters.

### 4.1 Auto-Configured `GlobalExceptionHandler`
Uses Spring Boot `@RestControllerAdvice` to catch:
- `BusinessException` and subclasses (`ConflictException`, `ResourceNotFoundException`, `ValidationException`).
- `MethodArgumentNotValidException` (Jakarta validation errors) -> maps to `ValidationError` list.
- `ConstraintViolationException`.
- `AccessDeniedException` -> maps to 403 Forbidden.
- `Exception` (catch-all) -> maps to 500 Internal Server Error with correlationId for log tracing.

### 4.2 Correlation Context & MDC
`CorrelationIdFilter` intercepts incoming HTTP requests, extracts `X-Correlation-Id` header (or generates a UUID), and sets it into `CorrelationContext` and SLF4J `MDC` (`traceId`, `correlationId`).

---

## 5. `common-security`

Auto-configures Spring Security OAuth2 Resource Server integration with Microsoft Entra External ID / OIDC.

### 5.1 Roles & Claims Converter
- Converts Microsoft Entra External ID / OIDC JWT roles into Spring Security `GrantedAuthority` (`ROLE_CUSTOMER`, `ROLE_ADMIN`).
- Constants defined in `SecurityRoles`:
  ```java
  public final class SecurityRoles {
      public static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
      public static final String ROLE_ADMIN = "ROLE_ADMIN";
  }
  ```

### 5.2 `UserContext` Helper
Utility methods to extract principal information from `SecurityContextHolder`:
```java
public final class UserContext {
    public static Optional<String> getCurrentUserId();
    public static Optional<String> getCurrentUserEmail();
    public static Set<String> getCurrentRoles();
    public static boolean hasRole(String role);
}
```
