package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Typed input for the ownership-safe read-only {@code getReservation} AI tool
 * (TASK-P15-005 section 3).
 */
@Schema(description = "Reservation lookup key holding the reservationId UUID string")
public record GetReservationRequest(

        @Schema(description = "Reservation UUID string, normally from a prior confirmation result",
                example = "123e4567-e89b-12d3-a456-426614174000")
        String reservationId
) {}
