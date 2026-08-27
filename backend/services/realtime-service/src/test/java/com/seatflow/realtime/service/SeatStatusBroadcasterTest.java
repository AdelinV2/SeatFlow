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
