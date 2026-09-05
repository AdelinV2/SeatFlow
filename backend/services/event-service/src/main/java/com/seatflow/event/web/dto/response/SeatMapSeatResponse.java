package com.seatflow.event.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A single seat within a seat-map section")
public record SeatMapSeatResponse(

    @Schema(description = "Seat UUID") UUID seatId,
    @Schema(description = "Human-readable row label") String rowLabel,
    @Schema(description = "Seat number within the row") Integer seatNumber,
    @Schema(description = "Grid column coordinate") Integer gridX,
    @Schema(description = "Grid row coordinate") Integer gridY,
    @Schema(description = "Whether the seat is currently bookable") Boolean isActive,
    @Schema(description = "Seat position X local to its section") BigDecimal positionX,
    @Schema(description = "Seat position Y local to its section") BigDecimal positionY

) {
    /**
     * Source-compatibility for pre-P11 callers/tests holding grid-only seats.
     * Applies the documented compatibility backfill ({@code position = grid * 44}).
     * Production mappings must use the canonical constructor with explicit positions.
     */
    public SeatMapSeatResponse(UUID seatId, String rowLabel, Integer seatNumber,
                               Integer gridX, Integer gridY, Boolean isActive) {
        this(seatId, rowLabel, seatNumber, gridX, gridY, isActive,
                gridX == null ? BigDecimal.ZERO : BigDecimal.valueOf(gridX * 44L),
                gridY == null ? BigDecimal.ZERO : BigDecimal.valueOf(gridY * 44L));
    }
}
