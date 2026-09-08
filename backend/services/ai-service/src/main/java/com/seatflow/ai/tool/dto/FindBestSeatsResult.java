package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic ranking outcome for one event session (TASK-P15-003 section 4.2).
 *
 * <p>{@code status} is {@code OK} with up to three ranked candidates, {@code NO_MATCH} with
 * deterministic relaxation hints (never a fabricated best match), or
 * {@code PRICING_SELECTION_REQUIRED} with the available categories.
 */
@Schema(description = "Deterministic best-seat ranking result snapshot")
public record FindBestSeatsResult(

        @Schema(description = "Session the snapshot was taken for") UUID eventSessionId,
        @Schema(description = "Snapshot instant (UTC)") Instant snapshotAt,
        @Schema(description = "Outcome: OK, NO_MATCH, or PRICING_SELECTION_REQUIRED") String status,
        @Schema(description = "Ranked candidate sets, at most 3") List<SeatCandidate> candidates,
        @Schema(description = "Deterministic relaxation hints for NO_MATCH; empty otherwise")
        List<String> relaxationHints,
        @Schema(description = "Sections whose pricing needs an explicit category choice; "
                + "mirrors the snapshot composition hints whenever ambiguous sections were seen")
        List<AvailableSeatsResult.PricingSelectionHint> pricingSelectionRequired
) {

    @Schema(description = "One ranked candidate seat set")
    public record SeatCandidate(

            @Schema(description = "Seat UUIDs in stable order") List<UUID> seatIds,
            @Schema(description = "Display summaries in the same order") List<AvailableSeatItem> seats,
            @Schema(description = "Exact set total in minor units") long totalPriceMinor,
            @Schema(description = "Single shared ISO-4217 currency") String currency,
            @Schema(description = "True only for same-section, same-row, consecutive seat numbers")
            boolean contiguous,
            @Schema(description = "Deterministic human-readable ranking reasons") List<String> reasons,
            @Schema(description = "1-based ranking position") int rankingPosition
    ) {}
}
