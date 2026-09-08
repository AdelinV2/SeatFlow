package com.seatflow.ai.proposal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-owned bounded in-memory proposal store (TASK-P15-005 section 5).
 *
 * <p>Single-instance portfolio deployment: no persistent AI database is introduced and a process
 * restart invalidates proposals safely (the map is memory-only). Rules:
 *
 * <ul>
 *   <li>one active proposal per conversation; creating a new one supersedes the old one;</li>
 *   <li>expired entries are removed lazily on access and/or by bounded scheduled cleanup;</li>
 *   <li>when max entries is reached after expired cleanup, the store evicts the oldest
 *       non-active entry deterministically (oldest creation, tie-broken by proposal ID); it
 *       never evicts another user's {@code ACTIVE} proposal to make room — creation fails
 *       instead so IDs/ownership can never transfer;</li>
 *   <li>raw JWT, Groq key, card/payment token, chain-of-thought and prompt history are not
 *       fields of {@link ReservationProposal} and are therefore never stored here;</li>
 *   <li>all reads are owner-bound; unknown IDs and other owners' IDs both yield {@code null}
 *       from {@link #getForOwner} so callers can answer {@code 404} (anti-enumeration).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalStore {

    private final ReservationProposalProperties properties;
    private final Clock clock;

    private final ConcurrentHashMap<UUID, ReservationProposal> proposals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> activeByConversation = new ConcurrentHashMap<>();

    /**
     * Creates and stores a new {@code ACTIVE} proposal, superseding any current active proposal
     * for the same conversation.
     */
    public synchronized ReservationProposal create(
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
        ensureCapacity();
        Instant now = clock.instant();
        ReservationProposal proposal = new ReservationProposal(
                UUID.randomUUID(), conversationId, ownerSubject, eventId, eventSessionId,
                List.copyOf(seatIds), seatDisplays, pricingTierIds, maxTotalPriceMinor,
                preferredCategory, strategy, totalPriceMinor, currency, now,
                now.plus(properties.ttl()), ProposalStatus.ACTIVE,
                UUID.randomUUID().toString(), null);
        UUID previousId = activeByConversation.get(conversationId);
        if (previousId != null) {
            proposals.computeIfPresent(previousId, (id, previous) -> {
                if (previous.status() == ProposalStatus.ACTIVE) {
                    log.info("AI proposal superseded: proposalId={}, conversationId={}",
                            previousId, conversationId);
                    return previous.withStatus(ProposalStatus.SUPERSEDED);
                }
                return previous;
            });
        }
        proposals.put(proposal.proposalId(), proposal);
        activeByConversation.put(conversationId, proposal.proposalId());
        log.info("AI proposal created: proposalId={}, conversationId={}, seats={}, totalMinor={}, activeCount={}",
                proposal.proposalId(), conversationId, seatIds.size(), totalPriceMinor, proposals.size());
        return proposal;
    }

    /**
     * Returns the {@code ACTIVE} unexpired proposal only when it exists and belongs to the owner.
     * Unknown IDs, other owners' IDs, and expired/superseded/consumed entries all yield
     * {@code null} so callers can answer {@code 404} without leaking existence. Expired entries
     * are marked {@code EXPIRED} and unlinked lazily.
     */
    public ReservationProposal getForOwner(UUID proposalId, String ownerSubject) {
        if (proposalId == null || ownerSubject == null || ownerSubject.isBlank()) {
            return null;
        }
        ReservationProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return null;
        }
        if (isExpired(proposal, clock.instant())) {
            markExpired(proposalId);
            return null;
        }
        if (!proposal.ownerSubject().equals(ownerSubject)) {
            log.warn("AI proposal cross-owner access denied: proposalId={}", proposalId);
            return null;
        }
        if (proposal.status() != ProposalStatus.ACTIVE) {
            return null;
        }
        return proposal;
    }

    /**
     * Owner-bound peek that returns the proposal regardless of lifecycle state (for precise
     * failure codes such as SUPERSEDED/CONSUMED/EXPIRED). Still returns {@code null} for unknown
     * IDs and other owners' IDs (anti-enumeration); callers must not reveal which case applied.
     */
    public ReservationProposal peekForOwner(UUID proposalId, String ownerSubject) {
        if (proposalId == null || ownerSubject == null || ownerSubject.isBlank()) {
            return null;
        }
        ReservationProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return null;
        }
        if (!proposal.ownerSubject().equals(ownerSubject)) {
            log.warn("AI proposal cross-owner peek denied: proposalId={}", proposalId);
            return null;
        }
        if (isExpired(proposal, clock.instant()) && proposal.status() == ProposalStatus.ACTIVE) {
            markExpired(proposalId);
            ReservationProposal updated = proposals.get(proposalId);
            return updated == null ? null : updated;
        }
        return proposals.get(proposalId);
    }

    /** Raw peek without owner check, for expiry handling only (never returns data to callers). */
    ReservationProposal peekRaw(UUID proposalId) {
        if (proposalId == null) {
            return null;
        }
        return proposals.get(proposalId);
    }

    /** Returns the current active proposal ID for a conversation, if any. */
    public UUID activeIdForConversation(UUID conversationId) {
        if (conversationId == null) {
            return null;
        }
        return activeByConversation.get(conversationId);
    }

    /** Marks the proposal {@code CONSUMED} with the authoritative reservation ID after known success. */
    public synchronized void markConsumed(UUID proposalId, UUID reservationId) {
        proposals.computeIfPresent(proposalId, (id, existing) -> {
            if (existing.status() != ProposalStatus.ACTIVE) {
                return existing;
            }
            log.info("AI proposal consumed: proposalId={}, reservationId={}", proposalId, reservationId);
            return existing.withReservationId(reservationId, ProposalStatus.CONSUMED);
        });
    }

    /**
     * Supersedes the conversation's active proposal without cancelling any real Reservation
     * Service hold (holds live in Reservation Service, never in this store).
     */
    public synchronized void supersedeForConversation(UUID conversationId, String ownerSubject) {
        if (conversationId == null) {
            return;
        }
        UUID activeId = activeByConversation.get(conversationId);
        if (activeId == null) {
            return;
        }
        ReservationProposal active = proposals.get(activeId);
        if (active == null) {
            activeByConversation.remove(conversationId, activeId);
            return;
        }
        if (ownerSubject != null && !active.ownerSubject().equals(ownerSubject)) {
            log.warn("AI proposal cross-owner supersede denied: conversationId={}", conversationId);
            return;
        }
        if (active.status() == ProposalStatus.ACTIVE) {
            proposals.computeIfPresent(activeId, (id, existing) ->
                    existing.status() == ProposalStatus.ACTIVE
                            ? existing.withStatus(ProposalStatus.SUPERSEDED) : existing);
            log.info("AI proposal superseded on reset/new proposal: proposalId={}, conversationId={}",
                    activeId, conversationId);
        }
        activeByConversation.remove(conversationId, activeId);
    }

    public boolean isExpired(ReservationProposal proposal, Instant now) {
        return proposal == null || !proposal.expiresAt().isAfter(now);
    }

    public int size() {
        return proposals.size();
    }

    /**
     * Bounded, thread-safe expiry sweep. Marks at most {@code maxToRemove} expired
     * {@code ACTIVE} entries as {@code EXPIRED} and unlinks them from the active index so the
     * store stays bounded. Non-active history is retained boundedly for precise failure codes
     * and is reclaimed by capacity eviction.
     */
    public synchronized int cleanupExpiredBounded(int maxToRemove) {
        Instant now = clock.instant();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, ReservationProposal> entry : proposals.entrySet()) {
            if (expired.size() >= maxToRemove) {
                break;
            }
            ReservationProposal proposal = entry.getValue();
            if (proposal.status() == ProposalStatus.ACTIVE && isExpired(proposal, now)) {
                expired.add(entry.getKey());
            }
        }
        expired.forEach(this::markExpired);
        if (!expired.isEmpty()) {
            log.info("AI proposal expiry sweep marked {} proposals expired, stored={}",
                    expired.size(), proposals.size());
        }
        return expired.size();
    }

    public int cleanupExpired() {
        return cleanupExpiredBounded(100);
    }

    private void markExpired(UUID proposalId) {
        proposals.computeIfPresent(proposalId, (id, existing) -> {
            if (existing.status() == ProposalStatus.ACTIVE) {
                activeByConversation.remove(existing.conversationId(), proposalId);
                return existing.withStatus(ProposalStatus.EXPIRED);
            }
            return existing;
        });
    }

    private void ensureCapacity() {
        cleanupExpiredBounded(100);
        if (proposals.size() < properties.maxActiveProposals()) {
            return;
        }
        // Deterministic safe eviction: oldest non-active entry first (creation, then ID).
        // Never evict another user's ACTIVE proposal — fail creation instead.
        proposals.values().stream()
                .filter(proposal -> proposal.status() != ProposalStatus.ACTIVE)
                .min(Comparator.comparing(ReservationProposal::createdAt)
                        .thenComparing(ReservationProposal::proposalId))
                .map(ReservationProposal::proposalId)
                .ifPresentOrElse(
                        oldest -> {
                            proposals.remove(oldest);
                            log.warn("AI proposal store at capacity ({}); evicted oldest non-active proposalId={}",
                                    properties.maxActiveProposals(), oldest);
                        },
                        () -> {
                            throw new IllegalStateException(
                                    "AI proposal store at capacity (" + properties.maxActiveProposals()
                                            + ") with only active proposals; rejecting creation");
                        });
        if (proposals.size() >= properties.maxActiveProposals()) {
            throw new IllegalStateException(
                    "AI proposal store at capacity (" + properties.maxActiveProposals() + ")");
        }
    }
}
