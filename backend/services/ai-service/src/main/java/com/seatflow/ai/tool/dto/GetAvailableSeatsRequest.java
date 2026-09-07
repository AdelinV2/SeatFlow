package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Typed input for the {@code getAvailableSeats} AI tool (TASK-P15-003 section 4.1).
 */
@Schema(description = "Criteria for reading authoritative available seats of one event session")
public record GetAvailableSeatsRequest(

        @Schema(description = "Session UUID string; the only valid inventory key",
                example = "123e4567-e89b-12d3-a456-426614174000")
        String eventSessionId,

        @Schema(description = "Optional section UUID string filter")
        String sectionId,

        @Schema(description = "Optional pricing category filter; exact match only")
        String category,

        @Schema(description = "Optional per-seat budget cap in minor units; seats priced above it are excluded")
        Long maxTotalPriceMinor,

        @Schema(description = "Optional ISO-4217 currency filter; seats in other currencies are excluded",
                example = "EUR")
        String currency,

        @Schema(description = "Maximum seats returned; server-clamped to 1..50, defaults to 20", example = "20")
        Integer limit
) {}
