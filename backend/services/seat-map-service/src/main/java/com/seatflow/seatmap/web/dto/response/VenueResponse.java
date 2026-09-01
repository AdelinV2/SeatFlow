package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Venue summary response for list views")
public record VenueResponse(
    @Schema(description = "Venue UUID") UUID id,
    @Schema(description = "Venue name") String name,
    @Schema(description = "Full street address") String address,
    @Schema(description = "City") String city,
    @Schema(description = "Country") String country,
    @Schema(description = "Total capacity") Integer capacity,
    @Schema(description = "Geographic latitude") Double latitude,
    @Schema(description = "Geographic longitude") Double longitude,
    @Schema(description = "Creation timestamp") Instant createdAt
) {
    public VenueResponse(UUID id, String name, String address, String city, String country, Integer capacity, Instant createdAt) {
        this(id, name, address, city, country, capacity, null, null, createdAt);
    }
}
