package com.seatflow.seatmap.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for updating an existing venue")
public record UpdateVenueRequest(

    @Schema(description = "Venue name", example = "Grand Theatre Updated")
    @Size(max = 255, message = "Venue name must not exceed 255 characters")
    String name,

    @Schema(description = "Full street address", example = "456 Broadway")
    @Size(max = 500, message = "Address must not exceed 500 characters")
    String address,

    @Schema(description = "City", example = "New York")
    @Size(max = 100, message = "City must not exceed 100 characters")
    String city,

    @Schema(description = "Country", example = "USA")
    @Size(max = 100, message = "Country must not exceed 100 characters")
    String country,

    @Schema(description = "Total venue capacity", example = "600")
    @Min(value = 1, message = "Capacity must be at least 1")
    Integer capacity

) {}
