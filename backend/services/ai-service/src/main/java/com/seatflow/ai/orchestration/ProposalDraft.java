package com.seatflow.ai.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Temporary orchestration/proposal draft created only from the last authoritative
 * {@code findBestSeats} output (TASK-P15-004 section 8).
 *
 * <p>Rules: never parse seat IDs/prices back out of model prose; quantity remains {@code 1..10};
 * the proposal is explicitly not a hold ({@code seatsHeld=false}); a changed user constraint
 * (event/session/quantity/budget/currency/section/category/strategy) supersedes the old draft; only
 * one current unconfirmed draft per conversation; exact secure storage/TTL/confirmation behavior is
 * finalized in P15-005.
 */
public record ProposalDraft(
        UUID draftId,
        UUID eventId,
        UUID eventSessionId,
        List<UUID> seatIds,
        List<String> seatLabels,
        String sectionSummary,
        long totalPriceMinor,
        String currency,
        boolean contiguous,
        List<String> reasons,
        int quantity,
        String constraintsFingerprint,
        Instant createdAt
) {
    public ProposalDraft {
        if (draftId == null) {
            throw new IllegalArgumentException("Draft ID is required");
        }
        if (eventSessionId == null) {
            throw new IllegalArgumentException("Event session ID is required");
        }
        if (seatIds == null || seatIds.isEmpty()) {
            throw new IllegalArgumentException("Draft seat IDs are required");
        }
        if (seatIds.size() < 1 || seatIds.size() > 10) {
            throw new IllegalArgumentException("Draft quantity must be between 1 and 10");
        }
        if (quantity < 1 || quantity > 10) {
            throw new IllegalArgumentException("Draft quantity must be between 1 and 10");
        }
        if (seatIds.size() != quantity) {
            throw new IllegalArgumentException("Draft seat count must match quantity");
        }
        seatIds = List.copyOf(seatIds);
        seatLabels = seatLabels == null ? List.of() : List.copyOf(seatLabels);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (createdAt == null) {
            throw new IllegalArgumentException("Draft creation time is required");
        }
    }
}
