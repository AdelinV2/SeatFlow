package com.seatflow.reservation.client.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Trusted booking facts for one event session, resolved from event-service
 * {@code GET /internal/event-sessions/{sessionId}/booking-context} (P12-002).
 *
 * <p>Reservation-service derives the parent event, hall/pricing scope, and
 * bookability exclusively from this response. A client-supplied {@code eventId}
 * is never authoritative; mismatch with {@link #eventId()} is a hard failure.
 */
public record SessionBookingContextDto(
        UUID eventSessionId,
        UUID eventId,
        String eventStatus,
        String sessionStatus,
        Instant startsAt,
        Instant endsAt,
        Instant saleStartsAt,
        Instant saleEndsAt,
        UUID venueId
) {
}
