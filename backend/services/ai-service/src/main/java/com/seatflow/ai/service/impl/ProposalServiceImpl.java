package com.seatflow.ai.service.impl;

import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposal;
import com.seatflow.ai.service.AiMetrics;
import com.seatflow.ai.service.ProposalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Secure proposal creation over the bounded in-memory store.
 *
 * <p>No JWT, provider secret, payment token, chain-of-thought, or prompt history ever reaches
 * this layer — the record type has no such fields. Quantity {@code 1..10} and currency shape
 * are re-validated here as defense in depth even though the orchestrator validated the
 * candidate first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalServiceImpl implements ProposalService {

    private final ProposalStore proposalStore;
    private final AiMetrics metrics;

    @Override
    public ReservationProposal createSecureProposal(
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
            String currency) {
        if (conversationId == null || ownerSubject == null || ownerSubject.isBlank()) {
            throw new IllegalArgumentException("Conversation ID and owner subject are required");
        }
        if (eventSessionId == null) {
            throw new IllegalArgumentException("Event session ID is required");
        }
        if (seatIds == null || seatIds.isEmpty() || seatIds.size() > 10) {
            throw new IllegalArgumentException("Proposal seats must contain between 1 and 10 entries");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Proposal currency must be a 3-letter ISO-4217 code");
        }
        ReservationProposal proposal = proposalStore.create(
                conversationId, ownerSubject, eventId, eventSessionId, seatIds, seatDisplays,
                pricingTierIds, maxTotalPriceMinor, preferredCategory, strategy,
                totalPriceMinor, currency);
        metrics.recordProposal(true);
        log.info("AI_PROPOSAL_CREATED proposalId={} conversationId={} seats={}",
                proposal.proposalId(), conversationId, seatIds.size());
        return proposal;
    }

    @Override
    public void supersedeForConversation(UUID conversationId, String ownerSubject) {
        proposalStore.supersedeForConversation(conversationId, ownerSubject);
    }

    @Override
    public ReservationProposal peekForOwner(UUID proposalId, String ownerSubject) {
        return proposalStore.peekForOwner(proposalId, ownerSubject);
    }
}
