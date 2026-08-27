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

    @Test
    @DisplayName("Factory methods should handle null seatIds or single null seat gracefully")
    void of_WithNullSeats_HandlesGracefully() {
        UUID eventId = UUID.randomUUID();

        SeatStatusUpdateMessage messageList = SeatStatusUpdateMessage.of(eventId, (List<UUID>) null, SeatStatus.AVAILABLE, null);
        assertNotNull(messageList.seatIds());
        assertTrue(messageList.seatIds().isEmpty());

        SeatStatusUpdateMessage messageSingle = SeatStatusUpdateMessage.of(eventId, (UUID) null, SeatStatus.AVAILABLE);
        assertNotNull(messageSingle.seatIds());
        assertTrue(messageSingle.seatIds().isEmpty());
    }

    @Test
    @DisplayName("Factory method 'of' should filter out null elements inside seatIds list")
    void of_WithListContainingNulls_FiltersNullElements() {
        UUID eventId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();
        java.util.List<UUID> listWithNull = java.util.Arrays.asList(seat1, null);

        SeatStatusUpdateMessage message = SeatStatusUpdateMessage.of(eventId, listWithNull, SeatStatus.AVAILABLE, null);

        assertEquals(List.of(seat1), message.seatIds());
    }

    @Test
    @DisplayName("Factory method 'of' should force holdExpiresAt to null if status is not HELD")
    void of_WithNonHeldStatusAndHoldExpiresAt_ResetsHoldExpiresAtToNull() {
        UUID eventId = UUID.randomUUID();
        UUID seat1 = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(900);

        SeatStatusUpdateMessage availableMessage = SeatStatusUpdateMessage.of(
                eventId,
                List.of(seat1),
                SeatStatus.AVAILABLE,
                expiresAt
        );
        assertNull(availableMessage.holdExpiresAt(), "holdExpiresAt must be null for AVAILABLE status");

        SeatStatusUpdateMessage soldMessage = SeatStatusUpdateMessage.of(
                eventId,
                List.of(seat1),
                SeatStatus.SOLD,
                expiresAt
        );
        assertNull(soldMessage.holdExpiresAt(), "holdExpiresAt must be null for SOLD status");
    }
}
