package com.seatflow.ai.client.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * AI-owned transport view of the server-derived session booking context
 * ({@code GET /internal/event-sessions/{sessionId}/booking-context}).
 *
 * <p>Statuses are transported as plain strings so {@code ai-service} never depends on Event
 * Service enum types. Field names mirror the reservation-service
 * {@code SessionBookingContextDto} wire shape.
 */
public record SessionBookingContextClientDto(
        UUID eventSessionId,
        UUID eventId,
        String eventStatus,
        String sessionStatus,
        Instant startsAt,
        Instant endsAt,
        Instant saleStartsAt,
        Instant saleEndsAt,
        UUID venueId
) {}
