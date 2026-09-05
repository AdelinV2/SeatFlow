package com.seatflow.reservation.client;

import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;

import java.util.Set;
import java.util.UUID;

public interface EventClient {

    EventPricingDetails getEventSeatPricing(UUID eventId, Set<UUID> requestedSeatIds);

    /**
     * Resolves the authoritative booking context for one event session.
     * The returned parent event id, statuses, and sales window are the only
     * trusted source for session validation; callers must not substitute
     * client-supplied event identity.
     */
    SessionBookingContextDto getSessionBookingContext(UUID eventSessionId);
}
