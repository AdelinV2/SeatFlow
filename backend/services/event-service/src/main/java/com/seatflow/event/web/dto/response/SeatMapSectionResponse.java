package com.seatflow.event.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "A seat-map section with its seats and pricing tiers")
public record SeatMapSectionResponse(

    @Schema(description = "Section UUID") UUID sectionId,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows in the section grid") Integer rowCount,
    @Schema(description = "Number of columns in the section grid") Integer colCount,
    @Schema(description = "Seats in this section") List<SeatMapSeatResponse> seats,
    @Schema(description = "Pricing tiers applicable to this section") List<PricingTierResponse> pricingTiers

) {}
