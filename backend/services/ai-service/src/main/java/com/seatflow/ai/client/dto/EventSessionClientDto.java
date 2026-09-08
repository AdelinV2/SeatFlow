package com.seatflow.ai.client.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * AI-owned transport view of one concrete scheduled showing of an event.
 *
 * <p>Timestamps are transported as {@link Instant} and must be preserved exactly (no timezone
 * conversion) when normalized for model consumption.
 */
public record EventSessionClientDto(
        UUID id,
        UUID eventId,
        Instant startsAt,
        Instant endsAt,
        Instant saleStartsAt,
        Instant saleEndsAt,
        String status,
        String timezone,
        Instant createdAt,
        Instant updatedAt
) {}
