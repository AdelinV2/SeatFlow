package com.seatflow.reservation.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PricingTierClientDto(
        UUID id,
        UUID sectionId,
        String categoryName,
        BigDecimal price,
        String currency
) {
}
