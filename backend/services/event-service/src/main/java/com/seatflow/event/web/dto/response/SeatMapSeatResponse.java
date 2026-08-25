package com.seatflow.event.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "A single seat within a seat-map section")
public record SeatMapSeatResponse(

    @Schema(description = "Seat UUID") UUID seatId,
    @Schema(description = "Human-readable row label") String rowLabel,
    @Schema(description = "Seat number within the row") Integer seatNumber,
    @Schema(description = "Grid column coordinate") Integer gridX,
    @Schema(description = "Grid row coordinate") Integer gridY,
    @Schema(description = "Whether the seat is currently bookable") Boolean isActive

) {}
