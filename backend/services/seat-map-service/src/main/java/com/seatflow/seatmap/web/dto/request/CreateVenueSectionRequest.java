package com.seatflow.seatmap.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for creating a venue section with automatic seat grid generation")
public record CreateVenueSectionRequest(

    @Schema(description = "Section name (e.g., 'Orchestra', 'Balcony', 'VIP Lounge')", example = "Orchestra")
    @NotBlank(message = "Section name is required")
    @Size(max = 100, message = "Section name must not exceed 100 characters")
    String name,

    @Schema(description = "Number of rows in the seat grid", example = "10")
    @NotNull(message = "Row count is required")
    @Min(value = 1, message = "Row count must be at least 1")
    Integer rowCount,

    @Schema(description = "Number of columns (seats per row) in the seat grid", example = "20")
    @NotNull(message = "Column count is required")
    @Min(value = 1, message = "Column count must be at least 1")
    Integer colCount

) {}
