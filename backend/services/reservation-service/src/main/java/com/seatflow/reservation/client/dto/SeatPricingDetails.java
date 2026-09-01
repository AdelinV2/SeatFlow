package com.seatflow.reservation.client.dto;

import java.util.List;
import java.util.UUID;

public record SeatPricingDetails(
        UUID sectionId,
        String sectionName,
        String rowLabel,
        Integer seatNumber,
        List<PricingTierClientDto> pricingTiers
) {
}
