package com.seatflow.event.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(description = "A seat-map section with its seats and pricing tiers")
public record SeatMapSectionResponse(

    @Schema(description = "Section UUID") UUID sectionId,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows in the section grid") Integer rowCount,
    @Schema(description = "Number of columns in the section grid") Integer colCount,
    @Schema(description = "Whether the section is active") Boolean isActive,
    @Schema(description = "Section position X on venue canvas") BigDecimal positionX,
    @Schema(description = "Section position Y on venue canvas") BigDecimal positionY,
    @Schema(description = "Section width") BigDecimal width,
    @Schema(description = "Section height") BigDecimal height,
    @Schema(description = "Section rotation in degrees") BigDecimal rotationDeg,
    @Schema(description = "Section z-index") Integer zIndex,
    @Schema(description = "Optional shape metadata JSON object") Object shapeMetadata,
    @Schema(description = "Seats in this section") List<SeatMapSeatResponse> seats,
    @Schema(description = "Pricing tiers applicable to this section") List<PricingTierResponse> pricingTiers

) {
    /**
     * Source-compatibility for pre-P11 callers/tests holding grid-only sections.
     * Applies the documented legacy derivation (origin at zero, 44-unit grid cells).
     * Production mappings must use the canonical constructor with explicit geometry.
     */
    public SeatMapSectionResponse(UUID sectionId, String name, Integer rowCount, Integer colCount,
                                  List<SeatMapSeatResponse> seats, List<PricingTierResponse> pricingTiers) {
        this(sectionId, name, rowCount, colCount, Boolean.TRUE,
                BigDecimal.ZERO, BigDecimal.ZERO,
                colCount == null ? null : BigDecimal.valueOf(colCount * 44L),
                rowCount == null ? null : BigDecimal.valueOf(rowCount * 44L),
                BigDecimal.ZERO, 0, null, seats, pricingTiers);
    }
}
