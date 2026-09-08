package com.seatflow.ai.proposal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative server-side reservation proposal (TASK-P15-005 sections 5-6).
 *
 * <p>Created only from the last authoritative {@code findBestSeats} output, never from model prose
 * or client-editable fields. The confirmation endpoint uses exactly these server-stored seat,
 * session, and price values; the confirmation request body must stay empty so clients cannot
 * alter seats or price.
 *
 * <p>Forbidden fields (never stored): raw JWT, Groq key, card/payment token, chain-of-thought,
 * complete prompt history. Process restart invalidates proposals because the store is in-memory
 * only; no persistent AI database is introduced.
 *
 * @param proposalId opaque server-generated ID used in {@code POST /api/ai/proposals/{id}/confirm}
 * @param conversationId owning conversation (one active proposal per conversation)
 * @param ownerSubject authenticated subject that owns both proposal and conversation
 * @param eventId parent event when known at proposal time (may be {@code null}; revalidation
 *     always resolves the authoritative event from the live session booking context)
 * @param eventSessionId authoritative inventory partition key
 * @param seatIds exact proposed seats in stable order, {@code 1..10}
 * @param seatDisplays display snapshot for the confirmation card (labels, never authority)
 * @param pricingTierIds resolved pricing tier IDs when the booking contract requires them
 * @param maxTotalPriceMinor original hard budget constraint in minor units, or {@code null}
 * @param preferredCategory original category constraint for revalidation, or {@code null}
 * @param strategy original ranking strategy label for audit, or {@code null}
 * @param totalPriceMinor exact proposal total in minor units (recomputed live before any write)
 * @param currency single shared ISO-4217 currency (uppercase)
 * @param createdAt creation instant (UTC)
 * @param expiresAt proposal expiry (creation + TTL; not a seat hold)
 * @param status lifecycle state
 * @param serverIdempotencyKey server-generated idempotency key reused for every retry of this
 *     proposal (never regenerated on timeout)
 * @param reservationId Reservation Service hold ID once success is authoritatively known, or
 *     {@code null} before success (used to reconcile duplicate confirms with the same result)
 */
public record ReservationProposal(
        UUID proposalId,
        UUID conversationId,
        String ownerSubject,
        UUID eventId,
        UUID eventSessionId,
        List<UUID> seatIds,
        List<SeatDisplay> seatDisplays,
        List<UUID> pricingTierIds,
        Long maxTotalPriceMinor,
        String preferredCategory,
        String strategy,
        long totalPriceMinor,
        String currency,
        Instant createdAt,
        Instant expiresAt,
        ProposalStatus status,
        String serverIdempotencyKey,
        UUID reservationId
) {
    /**
     * Customer-safe display snapshot for one proposed seat (confirmation card only).
     */
    public record SeatDisplay(
            UUID seatId,
            String label,
            String sectionName,
            String rowLabel,
            Integer seatNumber
    ) {}

    public ReservationProposal {
        if (proposalId == null) {
            throw new IllegalArgumentException("Proposal ID is required");
        }
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation ID is required");
        }
        if (ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("Owner subject is required");
        }
        if (eventSessionId == null) {
            throw new IllegalArgumentException("Event session ID is required");
        }
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > 10) {
            throw new IllegalArgumentException("Proposal seat IDs must contain between 1 and 10 entries");
        }
        if (seatIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("Proposal seat IDs must not contain null entries");
        }
        if (seatIds.size() != seatIds.stream().distinct().count()) {
            throw new IllegalArgumentException("Proposal seat IDs must not contain duplicates");
        }
        if (totalPriceMinor < 0) {
            throw new IllegalArgumentException("Proposal total must not be negative");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Proposal currency must be a 3-letter ISO-4217 code");
        }
        if (maxTotalPriceMinor != null && maxTotalPriceMinor < 0) {
            throw new IllegalArgumentException("Proposal budget constraint must not be negative");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Proposal creation time is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Proposal expiry time is required");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Proposal expiry must be after creation");
        }
        if (status == null) {
            throw new IllegalArgumentException("Proposal status is required");
        }
        if (serverIdempotencyKey == null || serverIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Server idempotency key is required");
        }
        seatIds = List.copyOf(seatIds);
        seatDisplays = seatDisplays == null ? List.of() : List.copyOf(seatDisplays);
        pricingTierIds = pricingTierIds == null ? List.of() : List.copyOf(pricingTierIds);
    }

    /** Returns a copy with the given lifecycle status (records are immutable). */
    public ReservationProposal withStatus(ProposalStatus next) {
        return new ReservationProposal(proposalId, conversationId, ownerSubject, eventId,
                eventSessionId, seatIds, seatDisplays, pricingTierIds, maxTotalPriceMinor,
                preferredCategory, strategy, totalPriceMinor, currency, createdAt, expiresAt,
                next, serverIdempotencyKey, reservationId);
    }

    /** Returns a copy recording the authoritative reservation hold ID after known success. */
    public ReservationProposal withReservationId(UUID reservationId, ProposalStatus next) {
        return new ReservationProposal(proposalId, conversationId, ownerSubject, eventId,
                eventSessionId, seatIds, seatDisplays, pricingTierIds, maxTotalPriceMinor,
                preferredCategory, strategy, totalPriceMinor, currency, createdAt, expiresAt,
                next, serverIdempotencyKey, reservationId);
    }
}
