package com.seatflow.ai.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AI-owned transport view of the public event catalog entry ({@code GET /api/events}).
 *
 * <p>Mirrors only the customer-safe fields of the Event Service {@code EventSummaryResponse}.
 * This type is owned by {@code ai-service}; it must never gain admin/internal fields merely
 * because an upstream DTO contains them.
 */
public record EventSummaryClientDto(
        UUID id,
        String title,
        String category,
        String bannerUrl,
        Instant nextSessionStartsAt,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String currency
) {}
