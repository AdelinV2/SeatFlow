package com.seatflow.ai.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * AI-owned transport view of the priced event seat map
 * ({@code GET /api/events/{eventId}/seat-map}).
 *
 * <p>Carries venue layout (seat identity, row/seat numbers, active flags, section geometry) plus
 * the event's section pricing tiers and non-bookable layout elements (stage geometry). Unknown
 * wire fields are ignored so additive upstream changes cannot break AI reads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatMapClientDto(
        UUID eventId,
        UUID venueId,
        String eventTitle,
        String status,
        String venueName,
        Integer venueCapacity,
        Long totalConfiguredSeats,
        List<SeatMapSection> sections,
        Long layoutVersion,
        List<SeatMapLayoutElement> layoutElements
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatMapSection(
            UUID sectionId,
            String name,
            Integer rowCount,
            Integer colCount,
            Boolean isActive,
            BigDecimal positionX,
            BigDecimal positionY,
            BigDecimal width,
            BigDecimal height,
            BigDecimal rotationDeg,
            Integer zIndex,
            List<SeatMapSeat> seats,
            List<PricingTierClientDto> pricingTiers
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatMapSeat(
            UUID seatId,
            String rowLabel,
            Integer seatNumber,
            Integer gridX,
            Integer gridY,
            Boolean isActive,
            BigDecimal positionX,
            BigDecimal positionY
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatMapLayoutElement(
            UUID elementId,
            String type,
            String label,
            SeatMapGeometry geometry,
            Integer zIndex
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeatMapGeometry(
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            BigDecimal rotationDeg
    ) {}
}
