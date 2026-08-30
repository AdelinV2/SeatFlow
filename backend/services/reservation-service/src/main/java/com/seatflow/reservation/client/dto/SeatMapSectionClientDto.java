package com.seatflow.reservation.client.dto;

import java.util.List;
import java.util.UUID;

public record SeatMapSectionClientDto(
        UUID sectionId,
        String name,
        Integer rowCount,
        Integer colCount,
        List<SeatMapSeatClientDto> seats,
        List<PricingTierClientDto> pricingTiers
) {
}
