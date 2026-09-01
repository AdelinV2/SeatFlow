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
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SeatStatusBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Captor
    private ArgumentCaptor<SeatStatusUpdateMessage> messageCaptor;

    private SeatStatusBroadcasterImpl broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new SeatStatusBroadcasterImpl(messagingTemplate, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
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

    @Test
    void connectionGaugeShouldBeIdempotentForDuplicateEvents() {
        SessionConnectEvent connect = connectEvent("session-1");
        SessionDisconnectEvent disconnect = disconnectEvent("session-1");

        broadcaster.onConnect(connect);
        broadcaster.onConnect(connect);
        assertEquals(1, broadcaster.getActiveConnections());

        broadcaster.onDisconnect(disconnect);
        broadcaster.onDisconnect(disconnect);
        assertEquals(0, broadcaster.getActiveConnections());
    }

    @Test
    void connectionGaugeShouldIgnoreDisconnectBeforeConnect() {
        broadcaster.onDisconnect(disconnectEvent("session-2"));

        assertEquals(0, broadcaster.getActiveConnections());

        broadcaster.onConnect(connectEvent("session-2"));
        assertEquals(1, broadcaster.getActiveConnections());
    }

    @Test
    void connectionGaugeShouldRemainConsistentUnderConcurrentLifecycleEvents() throws Exception {
        int sessions = 100;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int index = 0; index < sessions; index++) {
            String sessionId = "session-" + index;
            executor.submit(() -> {
                await(start);
                broadcaster.onConnect(connectEvent(sessionId));
                broadcaster.onConnect(connectEvent(sessionId));
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(sessions, broadcaster.getActiveConnections());

        ExecutorService disconnectors = Executors.newFixedThreadPool(8);
        for (int index = 0; index < sessions; index++) {
            String sessionId = "session-" + index;
            disconnectors.submit(() -> {
                broadcaster.onDisconnect(disconnectEvent(sessionId));
                broadcaster.onDisconnect(disconnectEvent(sessionId));
            });
        }
        disconnectors.shutdown();
        assertTrue(disconnectors.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(0, broadcaster.getActiveConnections());
    }

    private SessionConnectEvent connectEvent(String sessionId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT);
        headers.setSessionId(sessionId);
        return new SessionConnectEvent(this, MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));
    }

    private SessionDisconnectEvent disconnectEvent(String sessionId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT);
        headers.setSessionId(sessionId);
        return new SessionDisconnectEvent(this,
                MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()),
                sessionId,
                CloseStatus.NORMAL);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(exception);
        }
    }
}
