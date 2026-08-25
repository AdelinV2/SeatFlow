package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "Complete venue seat map layout with all sections and seats")
public record VenueSeatMapLayoutResponse(
    @Schema(description = "Venue UUID") UUID venueId,
    @Schema(description = "Venue name") String name,
    @Schema(description = "Total capacity") Integer capacity,
    @Schema(description = "Sections with seat grids") List<SectionLayoutResponse> sections
) {}
