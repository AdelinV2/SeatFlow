# TASK-P07-003: Realtime Broadcasting Service & Seat Status Event DTOs

## 1. Task Metadata
- **Task ID:** `TASK-P07-003`
- **Git Branch:** `feat/p07-003-broadcasting-service-and-dtos`
- **Target Module:** `backend/services/realtime-service`
- **Phase:** `Phase 07 - Realtime WebSocket Service`
- **Related Specs:** `.ai/architecture/02-microservices-spec.md` (Section 9: Realtime Service), `.ai/architecture/05-messaging-and-outbox.md` (Section 2.2)
- **Related ADRs:** `None`
- **Status:** `READY FOR IMPLEMENTATION`

---

## 2. Objective & Invariants
Create the domain data transfer models and broadcasting service layer responsible for pushing real-time seat availability updates to connected STOMP subscribers. The broadcasting service encapsulates `SimpMessagingTemplate` and routes structured `SeatStatusUpdateMessage` payloads to the topic destination `/topic/events/{eventId}/seats`. This decouples the inbound messaging layer (Kafka consumers) from WebSocket protocol details.

### Critical Invariants to Enforce:
- [ ] **STOMP Topic Destination Pattern:** All seat status updates must broadcast strictly to destination `/topic/events/{eventId}/seats`, where `{eventId}` is the aggregate UUID string of the event.
- [ ] **Seat Status Finite State Enum:** `SeatStatus` must be strictly defined as `AVAILABLE`, `HELD`, or `SOLD`.
- [ ] **Batch & Individual Seat Updates:** `SeatStatusUpdateMessage` contains `List<UUID> seatIds` allowing atomic multi-seat hold updates (e.g. when a customer reserves up to 10 seats simultaneously) or single-seat updates.
- [ ] **Hold Expiration Timestamp Propagation:** When broadcasting a `HELD` status, `holdExpiresAt` must propagate the 15-minute expiration timestamp so connected Angular seat maps can display active countdown timers. For `AVAILABLE` and `SOLD` statuses, `holdExpiresAt` is null.
- [ ] **Payload Validation:** Broadcaster must reject invalid calls with `IllegalArgumentException` if `eventId` is null, `seatIds` is null or empty, or `status` is null.
- [ ] **Shared Observability & MDC:** Broadcast log events must include structured metadata (eventId, seat count, status, destination) and maintain MDC correlation context.

---

## 3. Exact File Inventory
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/enums/SeatStatus.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/dto/SeatStatusUpdateMessage.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/service/SeatStatusBroadcaster.java`
- `[NEW]` `backend/services/realtime-service/src/main/java/com/seatflow/realtime/service/impl/SeatStatusBroadcasterImpl.java`
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/service/SeatStatusBroadcasterTest.java`
- `[NEW]` `backend/services/realtime-service/src/test/java/com/seatflow/realtime/dto/SeatStatusUpdateMessageTest.java`

---

## 4. Technical Specifications & Contracts

### 4.1 Enums & DTO Records

#### `com.seatflow.realtime.enums.SeatStatus`:
```java
package com.seatflow.realtime.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Real-time state of a physical venue seat")
public enum SeatStatus {
    AVAILABLE,
    HELD,
    SOLD
}
```

#### `com.seatflow.realtime.dto.SeatStatusUpdateMessage`:
```java
package com.seatflow.realtime.dto;

import com.seatflow.realtime.enums.SeatStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "STOMP broadcast payload for seat status updates on an event")
public record SeatStatusUpdateMessage(
        @Schema(description = "Event ID", example = "223e4567-e89b-12d3-a456-426614174000")
        UUID eventId,

        @Schema(description = "List of affected seat IDs")
        List<UUID> seatIds,

        @Schema(description = "Updated seat status", example = "HELD")
        SeatStatus status,

        @Schema(description = "Timestamp when the status update occurred")
        Instant timestamp,

        @Schema(description = "Expiration timestamp for HELD status (null for AVAILABLE or SOLD)")
        Instant holdExpiresAt
) {
    public static SeatStatusUpdateMessage of(
            UUID eventId,
            List<UUID> seatIds,
            SeatStatus status,
            Instant holdExpiresAt
    ) {
        return new SeatStatusUpdateMessage(
                eventId,
                seatIds != null ? List.copyOf(seatIds) : List.of(),
                status,
                Instant.now(),
                holdExpiresAt
        );
    }

    public static SeatStatusUpdateMessage of(
            UUID eventId,
            UUID seatId,
            SeatStatus status
    ) {
        return new SeatStatusUpdateMessage(
                eventId,
                seatId != null ? List.of(seatId) : List.of(),
                status,
                Instant.now(),
                null
        );
    }
}
```

---

### 4.2 Broadcaster Interface & Implementation Contracts

#### `com.seatflow.realtime.service.SeatStatusBroadcaster`:
```java
package com.seatflow.realtime.service;

import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SeatStatusBroadcaster {

    /**
     * Broadcasts a pre-constructed seat status update message to /topic/events/{eventId}/seats.
     *
     * @param message the seat status update payload
     */
    void broadcastSeatStatus(SeatStatusUpdateMessage message);

    /**
     * Broadcasts a status update for multiple seats.
     *
     * @param eventId       the event UUID
     * @param seatIds       the list of affected seat UUIDs
     * @param status        the new seat status
     * @param holdExpiresAt optional expiration timestamp if status is HELD
     */
    void broadcastSeatStatus(UUID eventId, List<UUID> seatIds, SeatStatus status, Instant holdExpiresAt);

    /**
     * Broadcasts a status update for a single seat.
     *
     * @param eventId the event UUID
     * @param seatId  the affected seat UUID
     * @param status  the new seat status
     */
    void broadcastSeatStatus(UUID eventId, UUID seatId, SeatStatus status);
}
```

#### `com.seatflow.realtime.service.impl.SeatStatusBroadcasterImpl`:
```java
package com.seatflow.realtime.service.impl;

import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatStatusBroadcasterImpl implements SeatStatusBroadcaster {

    private static final String DESTINATION_TEMPLATE = "/topic/events/%s/seats";
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void broadcastSeatStatus(SeatStatusUpdateMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("SeatStatusUpdateMessage must not be null");
        }
        if (message.eventId() == null) {
            throw new IllegalArgumentException("eventId must not be null");
        }
        if (message.seatIds() == null || message.seatIds().isEmpty()) {
            throw new IllegalArgumentException("seatIds must not be null or empty");
        }
        if (message.status() == null) {
            throw new IllegalArgumentException("status must not be null");
        }

        String destination = String.format(DESTINATION_TEMPLATE, message.eventId());

        log.info("Broadcasting seat status update: destination={}, status={}, seatCount={}, holdExpiresAt={}",
                destination, message.status(), message.seatIds().size(), message.holdExpiresAt());

        messagingTemplate.convertAndSend(destination, message);
    }

    @Override
    public void broadcastSeatStatus(UUID eventId, List<UUID> seatIds, SeatStatus status, Instant holdExpiresAt) {
        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventId, seatIds, status, holdExpiresAt);
        broadcastSeatStatus(message);
    }

    @Override
    public void broadcastSeatStatus(UUID eventId, UUID seatId, SeatStatus status) {
        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventId, seatId, status);
        broadcastSeatStatus(message);
    }
}
```

---

### 4.3 Unit Tests

#### `src/test/java/com/seatflow/realtime/service/SeatStatusBroadcasterTest.java`:
```java
package com.seatflow.realtime.service;

import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.service.impl.SeatStatusBroadcasterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeatStatusBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Captor
    private ArgumentCaptor<SeatStatusUpdateMessage> messageCaptor;

    private SeatStatusBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new SeatStatusBroadcasterImpl(messagingTemplate);
    }

    @Test
    @DisplayName("Should broadcast batch HELD seats to /topic/events/{eventId}/seats with hold expiration")
    void broadcastSeatStatus_BatchHeldSeats_SendsToCorrectTopic() {
        UUID eventId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Instant expiresAt = Instant.now().plusSeconds(900);

        broadcaster.broadcastSeatStatus(eventId, seatIds, SeatStatus.HELD, expiresAt);

        String expectedDestination = "/topic/events/" + eventId + "/seats";
        verify(messagingTemplate).convertAndSend(eq(expectedDestination), messageCaptor.capture());

        SeatStatusUpdateMessage sentMessage = messageCaptor.getValue();
        assertEquals(eventId, sentMessage.eventId());
        assertEquals(seatIds, sentMessage.seatIds());
        assertEquals(SeatStatus.HELD, sentMessage.status());
        assertEquals(expiresAt, sentMessage.holdExpiresAt());
        assertNotNull(sentMessage.timestamp());
    }

    @Test
    @DisplayName("Should broadcast single SOLD seat to /topic/events/{eventId}/seats with null expiration")
    void broadcastSeatStatus_SingleSoldSeat_SendsToCorrectTopic() {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        broadcaster.broadcastSeatStatus(eventId, seatId, SeatStatus.SOLD);

        String expectedDestination = "/topic/events/" + eventId + "/seats";
        verify(messagingTemplate).convertAndSend(eq(expectedDestination), messageCaptor.capture());

        SeatStatusUpdateMessage sentMessage = messageCaptor.getValue();
        assertEquals(eventId, sentMessage.eventId());
        assertEquals(List.of(seatId), sentMessage.seatIds());
        assertEquals(SeatStatus.SOLD, sentMessage.status());
        assertNull(sentMessage.holdExpiresAt());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is null")
    void broadcastSeatStatus_NullMessage_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> broadcaster.broadcastSeatStatus(null));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when eventId is null")
    void broadcastSeatStatus_NullEventId_ThrowsException() {
        SeatStatusUpdateMessage message = new SeatStatusUpdateMessage(
                null,
                List.of(UUID.randomUUID()),
                SeatStatus.AVAILABLE,
                Instant.now(),
                null
        );
        assertThrows(IllegalArgumentException.class, () -> broadcaster.broadcastSeatStatus(message));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when seatIds list is empty")
    void broadcastSeatStatus_EmptySeatIds_ThrowsException() {
        SeatStatusUpdateMessage message = new SeatStatusUpdateMessage(
                UUID.randomUUID(),
                List.of(),
                SeatStatus.AVAILABLE,
                Instant.now(),
                null
        );
        assertThrows(IllegalArgumentException.class, () -> broadcaster.broadcastSeatStatus(message));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when status is null")
    void broadcastSeatStatus_NullStatus_ThrowsException() {
        SeatStatusUpdateMessage message = new SeatStatusUpdateMessage(
                UUID.randomUUID(),
                List.of(UUID.randomUUID()),
                null,
                Instant.now(),
                null
        );
        assertThrows(IllegalArgumentException.class, () -> broadcaster.broadcastSeatStatus(message));
    }
}
```

#### `src/test/java/com/seatflow/realtime/dto/SeatStatusUpdateMessageTest.java`:
```java
package com.seatflow.realtime.dto;

import com.seatflow.realtime.enums.SeatStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SeatStatusUpdateMessageTest {

    @Test
    @DisplayName("Factory method 'of' with list should create immutable seat list and timestamp")
    void of_WithList_CreatesValidRecord() {
        UUID eventId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();
        UUID seat2 = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);

        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventId, List.of(seat1, seat2), SeatStatus.HELD, expiresAt);

        assertEquals(eventId, message.eventId());
        assertEquals(2, message.seatIds().size());
        assertEquals(SeatStatus.HELD, message.status());
        assertEquals(expiresAt, message.holdExpiresAt());
        assertNotNull(message.timestamp());
    }

    @Test
    @DisplayName("Factory method 'of' with single seat should wrap seat in singleton list")
    void of_WithSingleSeat_CreatesSingletonList() {
        UUID eventId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();

        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventId, seat1, SeatStatus.AVAILABLE);

        assertEquals(eventId, message.eventId());
        assertEquals(List.of(seat1), message.seatIds());
        assertEquals(SeatStatus.AVAILABLE, message.status());
        assertNull(message.holdExpiresAt());
        assertNotNull(message.timestamp());
    }
}
```

---

## 5. Step-by-Step Implementation Sequence (For Builder / Implementer)
1. **Enum Creation:** Create `com.seatflow.realtime.enums.SeatStatus` with `AVAILABLE`, `HELD`, and `SOLD`.
2. **DTO Record Creation:** Create `com.seatflow.realtime.dto.SeatStatusUpdateMessage` with static convenience factory methods (`of(...)`).
3. **Broadcaster Contract:** Create interface `com.seatflow.realtime.service.SeatStatusBroadcaster`.
4. **Broadcaster Implementation:** Create `com.seatflow.realtime.service.impl.SeatStatusBroadcasterImpl` injecting `SimpMessagingTemplate`, validating arguments, and sending to `/topic/events/{eventId}/seats`.
5. **Testing & Verification:**
   - Create `SeatStatusUpdateMessageTest.java` and `SeatStatusBroadcasterTest.java`.
   - Run verification command to ensure complete test coverage.

---

## 6. Definition of Done & Verification Command
To verify this task, run:
```bash
mvn clean test -pl backend/services/realtime-service -Dtest=SeatStatusUpdateMessageTest,SeatStatusBroadcasterTest
```
- [ ] `SeatStatus` enum accurately defines `AVAILABLE`, `HELD`, and `SOLD`.
- [ ] `SeatStatusUpdateMessage` supports batch seat updates, hold expiration timestamps, and creation timestamps.
- [ ] `SeatStatusBroadcasterImpl` formats STOMP destinations as `/topic/events/{eventId}/seats`.
- [ ] Argument validation rejects null messages, null `eventId`, empty `seatIds`, and null `status`.
- [ ] All unit tests pass with zero warnings.
- [ ] Task file is moved to `.ai/tasks/completed/phase-07-realtime-service/003-realtime-broadcasting-service-and-event-dtos.md`.
