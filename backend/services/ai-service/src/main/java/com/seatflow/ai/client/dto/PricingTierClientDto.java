package com.seatflow.ai.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * AI-owned transport view of one public pricing tier of an event.
 */
public record PricingTierClientDto(
        UUID id,
        UUID sectionId,
        String categoryName,
        BigDecimal price,
        String currency
) {}
