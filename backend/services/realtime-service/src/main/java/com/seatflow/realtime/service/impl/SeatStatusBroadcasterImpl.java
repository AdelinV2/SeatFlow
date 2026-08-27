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
