package com.seatflow.ai.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI-owned transport view of the published event detail ({@code GET /api/events/{eventId}}).
 *
 * <p>Contains only customer-safe fields. The current public Event Service contract exposes
 * {@code venueId} but no {@code venueName}, so no venue name is transported (it must not be
 * invented downstream).
 */
public record EventDetailClientDto(
        UUID id,
        UUID venueId,
        String title,
        String description,
        String category,
        String status,
        List<PricingTierClientDto> pricingTiers,
        List<EventSessionClientDto> sessions,
        Instant createdAt,
        Instant updatedAt
) {}
