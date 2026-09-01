package com.seatflow.seatmap.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
    Integer capacity,

    @Schema(description = "Geographic latitude", example = "44.4268")
    @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
    Double latitude,

    @Schema(description = "Geographic longitude", example = "26.1025")
    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
    Double longitude

) {
    public UpdateVenueRequest(String name, String address, String city, String country, Integer capacity) {
        this(name, address, city, country, capacity, null, null);
    }
}
