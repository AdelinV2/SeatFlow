package com.seatflow.seatmap.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Individual seat in a venue section")
public record SeatResponse(
    @Schema(description = "Seat UUID") UUID seatId,
    @Schema(description = "Row label (e.g., 'A', 'B', 'C')") String rowLabel,
    @Schema(description = "Seat number within the row") Integer seatNumber,
    @Schema(description = "Grid X coordinate (0-based column index)") Integer gridX,
    @Schema(description = "Grid Y coordinate (0-based row index)") Integer gridY,
    @Schema(description = "Whether the seat is active/bookable") Boolean isActive,
    @Schema(description = "Seat position X local to its section") BigDecimal positionX,
    @Schema(description = "Seat position Y local to its section") BigDecimal positionY
) {}
