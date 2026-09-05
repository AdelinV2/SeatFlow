package com.seatflow.realtime.service;

import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SeatStatusBroadcaster {

    /**
     * Canonical session destination for seat updates.
     */
    static String sessionDestination(UUID eventSessionId) {
        return "/topic/sessions/" + eventSessionId + "/seats";
    }

    /**
     * Broadcasts a pre-constructed seat status update message to
     * {@code /topic/sessions/{eventSessionId}/seats}.
     *
     * @param message the seat status update payload (eventSessionId is the routing key)
     */
    void broadcastSeatStatus(SeatStatusUpdateMessage message);

    /**
     * Broadcasts a status update for multiple seats to the canonical session topic.
     *
     * @param eventSessionId the event session UUID (routing key)
     * @param eventId        the catalog event UUID for audit compatibility (nullable, never routed on)
     * @param seatIds        the list of affected seat UUIDs
     * @param status         the new seat status
     * @param holdExpiresAt  optional expiration timestamp if status is HELD
     */
    void broadcastSeatStatus(UUID eventSessionId, UUID eventId, List<UUID> seatIds, SeatStatus status,
                             Instant holdExpiresAt);

    /**
     * Broadcasts a status update for a single seat to the canonical session topic.
     *
     * @param eventSessionId the event session UUID (routing key)
     * @param eventId        the catalog event UUID for audit compatibility (nullable, never routed on)
     * @param seatId         the affected seat UUID
     * @param status         the new seat status
     */
    void broadcastSeatStatus(UUID eventSessionId, UUID eventId, UUID seatId, SeatStatus status);
}
