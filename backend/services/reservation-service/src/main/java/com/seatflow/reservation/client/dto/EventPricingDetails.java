package com.seatflow.reservation.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EventPricingDetails(
        UUID eventId,
        String eventStatus,
        Instant eventDate,
        List<UUID> seatIds,
        Map<UUID, BigDecimal> seatPrices,
        Map<UUID, SeatPricingDetails> seatDetails
) {
    public EventPricingDetails(UUID eventId,
                               String eventStatus,
                               Instant eventDate,
                               List<UUID> seatIds,
                               Map<UUID, BigDecimal> seatPrices) {
        this(eventId, eventStatus, eventDate, seatIds, seatPrices, Map.of());
    }
}
