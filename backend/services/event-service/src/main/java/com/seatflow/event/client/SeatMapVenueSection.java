package com.seatflow.event.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SeatMapVenueSection(
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
        // Opaque shape metadata as Object (not JsonNode): the service HTTP runtime
        // maps JSON with Jackson 3 while the repo-wide JsonNode API is Jackson 2,
        // and seat-map-service emits this field as Object on the wire.
        Object shapeMetadata,
        List<SeatMapVenueSeat> seats
) {
    /**
     * Source-compatibility for pre-P11 callers/tests holding grid-only sections.
     * Applies the documented legacy derivation (origin at zero, 44-unit grid cells).
     * Production mappings must use the canonical constructor with explicit geometry.
     */
    public SeatMapVenueSection(UUID sectionId, String name, Integer rowCount, Integer colCount,
                               List<SeatMapVenueSeat> seats) {
        this(sectionId, name, rowCount, colCount, Boolean.TRUE,
                BigDecimal.ZERO, BigDecimal.ZERO,
                colCount == null ? null : BigDecimal.valueOf(colCount * 44L),
                rowCount == null ? null : BigDecimal.valueOf(rowCount * 44L),
                BigDecimal.ZERO, 0, null, seats);
    }
}
