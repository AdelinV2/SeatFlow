package com.seatflow.seatmap.web.dto.response;

import com.seatflow.seatmap.model.enums.LayoutElementType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Non-bookable visual layout element")
public record LayoutElementResponse(
    @Schema(description = "Element UUID") UUID elementId,
    @Schema(description = "Element type") LayoutElementType type,
    @Schema(description = "Display label (required for LABEL type)") String label,
    @Schema(description = "Typed element geometry") Geometry geometry,
    @Schema(description = "Element z-index") Integer zIndex
) {
    @Schema(description = "Typed rectangular geometry for layout elements")
    public record Geometry(
        @Schema(description = "Geometry X on venue canvas") BigDecimal x,
        @Schema(description = "Geometry Y on venue canvas") BigDecimal y,
        @Schema(description = "Geometry width") BigDecimal width,
        @Schema(description = "Geometry height") BigDecimal height,
        @Schema(description = "Geometry rotation in degrees") BigDecimal rotationDeg
    ) {}
}
