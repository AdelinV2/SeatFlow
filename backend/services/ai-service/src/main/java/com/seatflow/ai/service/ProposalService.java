package com.seatflow.ai.service;

import com.seatflow.ai.proposal.ReservationProposal;

import java.util.List;
import java.util.UUID;

/**
 * Server-side secure proposal boundary (TASK-P15-005 sections 5-6).
 *
 * <p>Proposals are created only from the last authoritative {@code findBestSeats} output, never
 * from model prose or client-editable fields. One active proposal per conversation; a new one
 * supersedes the old one. Reset supersedes the active proposal without cancelling any real
 * Reservation Service hold.
 */
public interface ProposalService {

    ReservationProposal createSecureProposal(
            UUID conversationId,
            String ownerSubject,
            UUID eventId,
            UUID eventSessionId,
            List<UUID> seatIds,
            List<ReservationProposal.SeatDisplay> seatDisplays,
            List<UUID> pricingTierIds,
            Long maxTotalPriceMinor,
            String preferredCategory,
            String strategy,
            long totalPriceMinor,
            String currency);

    void supersedeForConversation(UUID conversationId, String ownerSubject);

    ReservationProposal peekForOwner(UUID proposalId, String ownerSubject);
}
