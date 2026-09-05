package com.seatflow.reservation.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P12-007: authoritative seat prices for one event's seat map. There is
 * deliberately no event-level instant here; the seat-map payload carries
 * venue layout + pricing only, and bookability (session status, sale
 * windows, startsAt) is enforced from the trusted
 * {@link SessionBookingContextDto} in ReservationServiceImpl.
 */
public record EventPricingDetails(
        UUID eventId,
        String eventStatus,
        List<UUID> seatIds,
        Map<UUID, BigDecimal> seatPrices,
        Map<UUID, SeatPricingDetails> seatDetails
) {
    public EventPricingDetails(UUID eventId,
                               String eventStatus,
                               List<UUID> seatIds,
                               Map<UUID, BigDecimal> seatPrices) {
        this(eventId, eventStatus, seatIds, seatPrices, Map.of());
    }
}
