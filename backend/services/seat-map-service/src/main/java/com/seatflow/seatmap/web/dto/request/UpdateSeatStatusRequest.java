package com.seatflow.seatmap.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request body for toggling seat active/inactive status")
public record UpdateSeatStatusRequest(

    @Schema(description = "Whether the seat should be active (bookable) or inactive", example = "false")
    @NotNull(message = "Active status is required")
    Boolean isActive

) {}
