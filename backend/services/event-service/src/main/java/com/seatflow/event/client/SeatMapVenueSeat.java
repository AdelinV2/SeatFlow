package com.seatflow.event.client;

import java.math.BigDecimal;
import java.util.UUID;

public record SeatMapVenueSeat(
        UUID seatId,
        String rowLabel,
        Integer seatNumber,
        Integer gridX,
        Integer gridY,
        Boolean isActive,
        BigDecimal positionX,
        BigDecimal positionY
) {
    /**
     * Source-compatibility for pre-P11 callers/tests holding grid-only seats.
     * Applies the documented compatibility backfill ({@code position = grid * 44}).
     * Production mappings must use the canonical constructor with explicit positions.
     */
    public SeatMapVenueSeat(UUID seatId, String rowLabel, Integer seatNumber,
                            Integer gridX, Integer gridY, Boolean isActive) {
        this(seatId, rowLabel, seatNumber, gridX, gridY, isActive,
                gridToPosition(gridX), gridToPosition(gridY));
    }

    private static BigDecimal gridToPosition(Integer grid) {
        return grid == null ? BigDecimal.ZERO : BigDecimal.valueOf(grid * 44L);
    }
}
