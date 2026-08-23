# TASK-003: Common Events Module (EventEnvelope, Headers, Topics, DomainEvent)

## 1. Task Metadata
- **Target Module:** `backend/common/common-events`
- **Phase:** `Phase 0 - Foundation`
- **Related Specs:** `.ai/architecture/01-common-modules.md`, `.ai/architecture/05-messaging-and-outbox.md`, `backend/AGENTS.md`
- **Related ADRs:** N/A
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Create the `common-events` shared library establishing the universal messaging contract for all asynchronous communication across SeatFlow. Define the standard generic `EventEnvelope<T>`, domain event marker interface `DomainEvent`, transport header constants `EventHeaders`, and Kafka topic names `EventTopics`.

### Critical Invariants to Enforce:
- [ ] All Kafka messages must be wrapped inside `EventEnvelope<T>`.
- [ ] `EventEnvelope<T>` must be an immutable Java Record with factory helpers for clean construction.
- [ ] `DomainEvent` must be a marker interface implemented by all event payloads.
- [ ] Constants classes (`EventHeaders`, `EventTopics`) must be `final` with private constructors to prevent instantiation.
- [ ] Envelope serialization and deserialization must seamlessly support polymorphic Jackson JSON parsing.

---

## 3. Exact File Inventory
List of all files to create or modify:

- `[NEW]` `backend/common/common-events/pom.xml`
- `[NEW]` `backend/common/common-events/src/main/java/com/seatflow/common/events/DomainEvent.java`
- `[NEW]` `backend/common/common-events/src/main/java/com/seatflow/common/events/EventEnvelope.java`
- `[NEW]` `backend/common/common-events/src/main/java/com/seatflow/common/events/EventHeaders.java`
- `[NEW]` `backend/common/common-events/src/main/java/com/seatflow/common/events/EventTopics.java`
- `[NEW]` `backend/common/common-events/src/test/java/com/seatflow/common/events/EventEnvelopeSerializationTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Maven POM (`backend/common/common-events/pom.xml`)
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

    <artifactId>common-events</artifactId>
    <name>SeatFlow :: Common Events</name>
    <description>Universal event envelope, header contracts, and topic definitions</description>

    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
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

### 4.2 Marker Interface (`com.seatflow.common.events.DomainEvent`)
```java
package com.seatflow.common.events;

import java.io.Serializable;

/**
 * Marker interface for all SeatFlow domain event payloads.
 */
public interface DomainEvent extends Serializable {
}
```

### 4.3 Universal Event Envelope (`com.seatflow.common.events.EventEnvelope`)
```java
package com.seatflow.common.events;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
    String eventId,
    String eventType,
    Instant occurredAt,
    String correlationId,
    String causationId,
    String aggregateId,
    int version,
    T payload
) {
    public static final int CURRENT_VERSION = 1;

    public static <T extends DomainEvent> EventEnvelope<T> of(
            String eventType,
            String aggregateId,
            String correlationId,
            String causationId,
            T payload) {
        return new EventEnvelope<>(
            UUID.randomUUID().toString(),
            eventType,
            Instant.now(),
            correlationId,
            causationId,
            aggregateId,
            CURRENT_VERSION,
            payload
        );
    }

    public static <T extends DomainEvent> EventEnvelope<T> of(
            String eventType,
            String aggregateId,
            String correlationId,
            T payload) {
        return of(eventType, aggregateId, correlationId, null, payload);
    }
}
```

### 4.4 Header Constants (`com.seatflow.common.events.EventHeaders`)
```java
package com.seatflow.common.events;

public final class EventHeaders {
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String CAUSATION_ID = "X-Causation-Id";
    public static final String EVENT_ID = "X-Event-Id";
    public static final String EVENT_TYPE = "X-Event-Type";

    private EventHeaders() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
```

### 4.5 Topic Constants (`com.seatflow.common.events.EventTopics`)
```java
package com.seatflow.common.events;

public final class EventTopics {
    public static final String RESERVATION_EVENTS = "seatflow.reservation.events";
    public static final String PAYMENT_EVENTS = "seatflow.payment.events";
    public static final String TICKET_EVENTS = "seatflow.ticket.events";
    public static final String NOTIFICATION_EVENTS = "seatflow.notification.events";

    private EventTopics() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Step 1:** Create `backend/common/common-events/pom.xml`.
2. **Step 2:** Define `DomainEvent` marker interface.
3. **Step 3:** Implement `EventEnvelope<T>` record with builder/factory methods.
4. **Step 4:** Implement `EventHeaders` and `EventTopics` constants.
5. **Step 5:** Write unit tests (`EventEnvelopeSerializationTest`) testing Jackson serialization/deserialization with `JavaTimeModule` (ISO-8601 UTC timestamp check, payload retention, null field checks).
6. **Step 6:** Run tests and verify clean build.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl common/common-events
```
- [ ] `EventEnvelope` serializes and deserializes accurately with Jackson.
- [ ] All constants classes enforce private constructor guards.
- [ ] Task file is moved to `.ai/tasks/completed/`.
