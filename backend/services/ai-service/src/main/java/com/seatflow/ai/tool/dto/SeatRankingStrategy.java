package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Canonical deterministic ranking strategies for Phase 15 (TASK-P15-003 section 4.2).
 *
 * <p>No subjective strategy may be added without a deterministic definition. The model may choose
 * among these strategies and explain returned results, but it never computes ranking itself.
 */
@Schema(description = "Deterministic best-seat ranking strategy")
public enum SeatRankingStrategy {
    CLOSEST_TO_STAGE,
    MOST_CENTRAL,
    BEST_VALUE
}
