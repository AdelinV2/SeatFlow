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
    @DisplayName("Factory method 'of' with list should create session-keyed message with timestamp")
    void of_WithList_CreatesValidRecord() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();
        UUID seat2 = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);

        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventSessionId, eventId,
                List.of(seat1, seat2), SeatStatus.HELD, expiresAt);

        assertEquals(eventSessionId, message.eventSessionId());
        assertEquals(eventId, message.eventId());
        assertEquals(2, message.seatIds().size());
        assertEquals(SeatStatus.HELD, message.status());
        assertEquals(expiresAt, message.holdExpiresAt());
        assertNotNull(message.timestamp());
    }

    @Test
    @DisplayName("Factory method 'of' with single seat should wrap seat in singleton list")
    void of_WithSingleSeat_CreatesSingletonList() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();

        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventSessionId, eventId, seat1,
                SeatStatus.AVAILABLE);

        assertEquals(eventSessionId, message.eventSessionId());
        assertEquals(eventId, message.eventId());
        assertEquals(List.of(seat1), message.seatIds());
        assertEquals(SeatStatus.AVAILABLE, message.status());
        assertNull(message.holdExpiresAt());
        assertNotNull(message.timestamp());
    }

    @Test
    @DisplayName("eventId is audit-only and may be null while eventSessionId remains the routing key")
    void of_WithNullEventId_KeepsSessionRoutingKey() {
        UUID eventSessionId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();

        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventSessionId, null,
                List.of(seat1), SeatStatus.SOLD, null);

        assertEquals(eventSessionId, message.eventSessionId());
        assertNull(message.eventId());
        assertEquals(List.of(seat1), message.seatIds());
    }

    @Test
    @DisplayName("Factory methods should handle null seatIds or single null seat gracefully")
    void of_WithNullSeats_HandlesGracefully() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        SeatStatusUpdateMessage messageList = SeatStatusUpdateMessage.of(eventSessionId, eventId,
                (List<UUID>) null, SeatStatus.AVAILABLE, null);
        assertNotNull(messageList.seatIds());
        assertTrue(messageList.seatIds().isEmpty());

        SeatStatusUpdateMessage messageSingle = SeatStatusUpdateMessage.of(eventSessionId, eventId,
                (UUID) null, SeatStatus.AVAILABLE);
        assertNotNull(messageSingle.seatIds());
        assertTrue(messageSingle.seatIds().isEmpty());
    }

    @Test
    @DisplayName("Factory method 'of' should filter out null elements inside seatIds list")
    void of_WithListContainingNulls_FiltersNullElements() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();
        java.util.List<UUID> listWithNull = java.util.Arrays.asList(seat1, null);

        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventSessionId, eventId, listWithNull,
                SeatStatus.AVAILABLE, null);

        assertEquals(List.of(seat1), message.seatIds());
    }

    @Test
    @DisplayName("Factory method 'of' should force holdExpiresAt to null if status is not HELD")
    void of_WithNonHeldStatusAndHoldExpiresAt_ResetsHoldExpiresAtToNull() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);

        SeatStatusUpdateMessage availableMessage = SeatStatusUpdateMessage.of(
                eventSessionId,
                eventId,
                List.of(seat1),
                SeatStatus.AVAILABLE,
                expiresAt
        );
        assertNull(availableMessage.holdExpiresAt(), "holdExpiresAt must be null for AVAILABLE status");

        SeatStatusUpdateMessage soldMessage = SeatStatusUpdateMessage.of(
                eventSessionId,
                eventId,
                List.of(seat1),
                SeatStatus.SOLD,
                expiresAt
        );
        assertNull(soldMessage.holdExpiresAt(), "holdExpiresAt must be null for SOLD status");
    }
}
