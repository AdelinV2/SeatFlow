package com.seatflow.ai.service.seat;

import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.ai.tool.dto.SeatRankingStrategy;

import java.util.UUID;

/**
 * Pure deterministic best-seat ranking over an explicit {@link SeatSnapshot}.
 *
 * <p>Implementations perform no I/O, read no clock, and call no model: ranking is a pure function
 * of the snapshot plus the validated query, so identical inputs always produce identical
 * ordering. All hard constraints (TASK-P15-003 section 5) are enforced before scoring.
 */
public interface SeatRankingService {

    FindBestSeatsResult rank(SeatSnapshot snapshot, ValidatedBestSeatsQuery query);

    /**
     * Validated ranking query: quantity is already checked to be {@code 1..10}, the strategy is
     * non-null, and UUID/currency/name filters are normalized. Built only by the service layer.
     */
    record ValidatedBestSeatsQuery(
            int quantity,
            Long maxTotalPriceMinor,
            String currency,
            UUID preferredSectionId,
            String preferredSectionName,
            String preferredCategory,
            SeatRankingStrategy strategy
    ) {}
}
