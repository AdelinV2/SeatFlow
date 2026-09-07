package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Compact authoritative seat snapshot for one event session (TASK-P15-003 section 4.1).
 *
 * <p>A snapshot, not a hold: another user may reserve a seat after ranking, so confirmation must
 * revalidate availability and price before any reservation is created.
 */
@Schema(description = "Authoritative available-seat snapshot for one event session")
public record AvailableSeatsResult(

        @Schema(description = "Session the snapshot was taken for") UUID eventSessionId,
        @Schema(description = "Snapshot instant (UTC)") Instant snapshotAt,
        @Schema(description = "Single unambiguous currency when one applies to the whole result")
        String currency,
        @Schema(description = "Available seats, bounded by the requested limit") List<AvailableSeatItem> seats,
        @Schema(description = "Sections whose pricing needs an explicit category choice; empty when none")
        List<PricingSelectionHint> pricingSelectionRequired
) {

    @Schema(description = "One section whose price cannot be resolved without an explicit category")
    public record PricingSelectionHint(

            @Schema(description = "Section UUID") UUID sectionId,
            @Schema(description = "Section name") String sectionName,
            @Schema(description = "Available pricing categories to choose from") List<String> availableCategories
    ) {}
}
