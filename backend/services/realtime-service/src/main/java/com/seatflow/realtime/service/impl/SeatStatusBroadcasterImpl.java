package com.seatflow.realtime.service.impl;

import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatStatusBroadcasterImpl implements SeatStatusBroadcaster {

    private static final String DESTINATION_TEMPLATE = "/topic/events/%s/seats";
    private final SimpMessagingTemplate messagingTemplate;
    private final MeterRegistry meterRegistry;

    private final Set<String> activeSessionIds = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void initGauge() {
        try {
            Gauge.builder(SeatFlowMetricNames.WEBSOCKET_ACTIVE_CONNECTIONS, activeSessionIds, Set::size)
                    .strongReference(true)
                    .description("Current number of active WebSocket sessions")
                    .register(meterRegistry);
        } catch (Exception ignored) {
            // metrics must never break business flow
        }
    }

    @EventListener
    public void onConnect(SessionConnectEvent event) {
        try {
            String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
            if (sessionId != null) {
                activeSessionIds.add(sessionId);
            }
            log.debug("WebSocket session connected. activeConnections={}", activeSessionIds.size());
        } catch (Exception ignored) {
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        try {
            String sessionId = event.getSessionId();
            if (sessionId != null) {
                activeSessionIds.remove(sessionId);
            }
            log.debug("WebSocket session disconnected. activeConnections={}", activeSessionIds.size());
        } catch (Exception ignored) {
        }
    }

    // Visible for testing
    public int getActiveConnections() {
        return activeSessionIds.size();
    }

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
