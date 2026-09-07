package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Typed input for the {@code findBestSeats} AI tool (TASK-P15-003 section 4.2).
 */
@Schema(description = "Criteria for deterministic best-seat ranking within one event session")
public record FindBestSeatsRequest(

        @Schema(description = "Session UUID string; the only valid inventory key",
                example = "123e4567-e89b-12d3-a456-426614174000")
        String eventSessionId,

        @Schema(description = "Requested seat count, 1..10 inclusive", example = "2")
        Integer quantity,

        @Schema(description = "Optional total budget cap in minor units for the whole set")
        Long maxTotalPriceMinor,

        @Schema(description = "Optional ISO-4217 currency; all seats in one set share it", example = "EUR")
        String currency,

        @Schema(description = "Optional preferred section UUID string")
        String preferredSectionId,

        @Schema(description = "Optional preferred section name; exact match")
        String preferredSectionName,

        @Schema(description = "Optional preferred pricing category; exact match")
        String preferredCategory,

        @Schema(description = "Deterministic ranking strategy", example = "CLOSEST_TO_STAGE")
        SeatRankingStrategy strategy
) {}
