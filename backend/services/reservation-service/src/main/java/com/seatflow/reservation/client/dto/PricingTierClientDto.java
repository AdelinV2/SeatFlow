package com.seatflow.reservation.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PricingTierClientDto(
        UUID tierId,
        String tierName,
        BigDecimal price,
        String currency
) {
}
