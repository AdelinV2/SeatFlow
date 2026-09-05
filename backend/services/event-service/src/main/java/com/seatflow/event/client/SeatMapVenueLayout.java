package com.seatflow.event.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SeatMapVenueLayout(
        UUID venueId,
        String name,
        Integer capacity,
        Long totalConfiguredSeats,
        List<SeatMapVenueSection> sections,
        Long layoutVersion,
        List<LayoutElement> elements
) {
    /**
     * Source-compatibility for pre-P11 callers/tests without layout versioning.
     * Production mappings must use the canonical constructor with explicit version/elements.
     */
    public SeatMapVenueLayout(UUID venueId, String name, Integer capacity, Long totalConfiguredSeats,
                              List<SeatMapVenueSection> sections) {
        this(venueId, name, capacity, totalConfiguredSeats, sections, 0L, List.of());
    }

    /**
     * Venue-level non-bookable visual element. Never carries pricing or seat status.
     * {@code type} stays a String so unknown future element types pass through
     * for forward-safe rendering instead of failing deserialization.
     */
    public record LayoutElement(
            UUID elementId,
            String type,
            String label,
            Geometry geometry,
            Integer zIndex
    ) {
    }

    public record Geometry(
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            BigDecimal rotationDeg
    ) {
    }
}
