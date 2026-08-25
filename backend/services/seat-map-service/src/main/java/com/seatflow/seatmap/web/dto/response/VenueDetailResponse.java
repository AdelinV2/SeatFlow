package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Venue detail response including sections")
public record VenueDetailResponse(
    @Schema(description = "Venue UUID") UUID id,
    @Schema(description = "Venue name") String name,
    @Schema(description = "Full street address") String address,
    @Schema(description = "City") String city,
    @Schema(description = "Country") String country,
    @Schema(description = "Total capacity") Integer capacity,
    @Schema(description = "Venue sections") List<VenueSectionResponse> sections,
    @Schema(description = "Creation timestamp") Instant createdAt
) {}
