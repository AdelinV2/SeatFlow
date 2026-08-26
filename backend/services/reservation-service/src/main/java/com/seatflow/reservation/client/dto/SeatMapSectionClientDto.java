package com.seatflow.reservation.client.dto;

import java.util.List;
import java.util.UUID;

public record SeatMapSectionClientDto(
        UUID sectionId,
        String sectionName,
        List<PricingTierClientDto> pricingTiers,
        List<SeatMapSeatClientDto> seats
) {
}
