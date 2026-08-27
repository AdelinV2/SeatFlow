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
