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
import static org.mockito.Mockito.never;
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
    @DisplayName("Should broadcast batch HELD seats to /topic/sessions/{eventSessionId}/seats with hold expiration")
    void broadcastSeatStatus_BatchHeldSeats_SendsToCorrectTopic() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Instant expiresAt = Instant.now().plusSeconds(900);

        broadcaster.broadcastSeatStatus(eventSessionId, eventId, seatIds, SeatStatus.HELD, expiresAt);

        String expectedDestination = "/topic/sessions/" + eventSessionId + "/seats";
        verify(messagingTemplate).convertAndSend(eq(expectedDestination), messageCaptor.capture());

        SeatStatusUpdateMessage sentMessage = messageCaptor.getValue();
        assertEquals(eventSessionId, sentMessage.eventSessionId());
        assertEquals(eventId, sentMessage.eventId());
        assertEquals(seatIds, sentMessage.seatIds());
        assertEquals(SeatStatus.HELD, sentMessage.status());
        assertEquals(expiresAt, sentMessage.holdExpiresAt());
        assertNotNull(sentMessage.timestamp());
    }

    @Test
    @DisplayName("Should broadcast single SOLD seat to /topic/sessions/{eventSessionId}/seats with null expiration")
    void broadcastSeatStatus_SingleSoldSeat_SendsToCorrectTopic() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        broadcaster.broadcastSeatStatus(eventSessionId, eventId, seatId, SeatStatus.SOLD);

        String expectedDestination = "/topic/sessions/" + eventSessionId + "/seats";
        verify(messagingTemplate).convertAndSend(eq(expectedDestination), messageCaptor.capture());

        SeatStatusUpdateMessage sentMessage = messageCaptor.getValue();
        assertEquals(eventSessionId, sentMessage.eventSessionId());
        assertEquals(List.of(seatId), sentMessage.seatIds());
        assertEquals(SeatStatus.SOLD, sentMessage.status());
        assertNull(sentMessage.holdExpiresAt());
    }

    @Test
    @DisplayName("Session A message goes only to /topic/sessions/A/seats and never to session B")
    void broadcastSeatStatus_SessionA_DoesNotLeakToSessionB() {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sharedSeatId = UUID.randomUUID();

        broadcaster.broadcastSeatStatus(sessionA, eventId, List.of(sharedSeatId), SeatStatus.HELD,
                Instant.now().plusSeconds(900));

        verify(messagingTemplate).convertAndSend(eq("/topic/sessions/" + sessionA + "/seats"), messageCaptor.capture());
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/sessions/" + sessionB + "/seats"), messageCaptor.capture());
        assertEquals(sessionA, messageCaptor.getValue().eventSessionId());
    }

    @Test
    @DisplayName("Same seat UUID in sessions A and B routes to distinct destinations without collision")
    void broadcastSeatStatus_SameSeatInTwoSessions_RoutesToDistinctDestinations() {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sharedSeatId = UUID.randomUUID();

        broadcaster.broadcastSeatStatus(sessionA, eventId, List.of(sharedSeatId), SeatStatus.HELD,
                Instant.now().plusSeconds(900));
        broadcaster.broadcastSeatStatus(sessionB, eventId, List.of(sharedSeatId), SeatStatus.SOLD, null);

        verify(messagingTemplate).convertAndSend(eq("/topic/sessions/" + sessionA + "/seats"), messageCaptor.capture());
        verify(messagingTemplate).convertAndSend(eq("/topic/sessions/" + sessionB + "/seats"), messageCaptor.capture());
    }

    @Test
    @DisplayName("Should never build a /topic/events/{sessionId} destination from a session identity")
    void broadcastSeatStatus_NeverUsesAmbiguousEventSessionPath() {
        UUID eventSessionId = UUID.randomUUID();

        broadcaster.broadcastSeatStatus(eventSessionId, null, List.of(UUID.randomUUID()), SeatStatus.AVAILABLE, null);

        verify(messagingTemplate, never()).convertAndSend(eq("/topic/events/" + eventSessionId + "/seats"),
                messageCaptor.capture());
        verify(messagingTemplate).convertAndSend(eq("/topic/sessions/" + eventSessionId + "/seats"),
                messageCaptor.capture());
    }

    @Test
    @DisplayName("Broadcast payload exposes no private reservation, customer, or payment data")
    void broadcastSeatStatus_PayloadContainsNoPrivateData() {
        UUID eventSessionId = UUID.randomUUID();

        broadcaster.broadcastSeatStatus(eventSessionId, UUID.randomUUID(), List.of(UUID.randomUUID()),
                SeatStatus.HELD, Instant.now().plusSeconds(900));

        verify(messagingTemplate).convertAndSend(eq("/topic/sessions/" + eventSessionId + "/seats"),
                messageCaptor.capture());
        SeatStatusUpdateMessage sentMessage = messageCaptor.getValue();
        String serialized = sentMessage.toString().toLowerCase();
        assertFalse(serialized.contains("customer"));
        assertFalse(serialized.contains("payment"));
        assertFalse(serialized.contains("email"));
        assertNotNull(sentMessage.eventSessionId());
        assertNotNull(sentMessage.seatIds());
        assertNotNull(sentMessage.status());
        assertNotNull(sentMessage.timestamp());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when message is null")
    void broadcastSeatStatus_NullMessage_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> broadcaster.broadcastSeatStatus(null));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when eventSessionId is null (event-only never routed)")
    void broadcastSeatStatus_NullEventSessionId_ThrowsException() {
        SeatStatusUpdateMessage message = new SeatStatusUpdateMessage(
                null,
                UUID.randomUUID(),
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
