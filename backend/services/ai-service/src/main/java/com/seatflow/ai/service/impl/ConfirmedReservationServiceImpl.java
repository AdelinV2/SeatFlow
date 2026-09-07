package com.seatflow.ai.service.impl;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.ReservationServiceClient;
import com.seatflow.ai.client.dto.ReservationServiceReservationDto;
import com.seatflow.ai.client.dto.SessionBookingContextClientDto;
import com.seatflow.ai.client.exception.ReservationServiceUnavailableException;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.orchestration.ConversationStore;
import com.seatflow.ai.proposal.ProposalStatus;
import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposal;
import com.seatflow.ai.service.ConfirmedReservationService;
import com.seatflow.ai.service.ProposalConfirmationCode;
import com.seatflow.ai.api.dto.ReservationCreatedCard;
import com.seatflow.ai.service.seat.PricedSeat;
import com.seatflow.ai.service.seat.SeatCandidateAssembler;
import com.seatflow.common.domain.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Explicit-confirmation reservation boundary (TASK-P15-005 sections 6-11).
 *
 * <p>Authorization order: authenticated caller, proposal owner, conversation owner, {@code ACTIVE}
 * status, unexpired TTL, then synchronous live revalidation (session bookable, exact seats still
 * {@code AVAILABLE}, quantity {@code 1..10}, same-currency pricing via the exact P15-003 rules,
 * recomputed total exactly equal, stored budget/category constraints still passing, no silent
 * substitution). Only then is exactly one Reservation Service call made with server-derived
 * values and the proposal's server-generated idempotency key.
 *
 * <p>Idempotency: the key is generated at proposal creation and reused for every retry;
 * {@code CONSUMED} is set only after success is authoritatively known; timeout-after-submit
 * yields {@code RESERVATION_RESULT_UNKNOWN_RETRY_SAFE} without claiming success/failure;
 * repeated confirms after known success reconcile to the same reservation result.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmedReservationServiceImpl implements ConfirmedReservationService {

    private final ProposalStore proposalStore;
    private final ConversationStore conversationStore;
    private final EventServiceClient eventServiceClient;
    private final SeatCandidateAssembler seatAssembler;
    private final ReservationServiceClient reservationServiceClient;
    private final Clock clock;

    @Override
    public ConfirmationOutcome confirmProposal(
            UUID proposalId, String ownerSubject, AiRequestContext context) {
        requireAuthenticated(context, ownerSubject);
        if (proposalId == null) {
            return failure(ProposalConfirmationCode.PROPOSAL_NOT_FOUND,
                    "Proposal not found. Please request fresh seats.");
        }

        ReservationProposal proposal = proposalStore.peekForOwner(proposalId, ownerSubject);
        if (proposal == null) {
            // Unknown ID and cross-owner ID are indistinguishable (anti-enumeration).
            return failure(ProposalConfirmationCode.PROPOSAL_NOT_FOUND,
                    "Proposal not found. Please request fresh seats.");
        }
        if (!proposal.ownerSubject().equals(ownerSubject)) {
            return failure(ProposalConfirmationCode.PROPOSAL_FORBIDDEN,
                    "Proposal not found. Please request fresh seats.");
        }
        if (conversationStore.getForOwner(proposal.conversationId(), ownerSubject) == null) {
            return failure(ProposalConfirmationCode.PROPOSAL_FORBIDDEN,
                    "Proposal not found. Please request fresh seats.");
        }

        Instant now = clock.instant();
        if (proposal.status() == ProposalStatus.EXPIRED || !proposal.expiresAt().isAfter(now)) {
            return failure(ProposalConfirmationCode.PROPOSAL_EXPIRED,
                    "This proposal expired. Please request fresh seats.");
        }
        if (proposal.status() == ProposalStatus.SUPERSEDED) {
            return failure(ProposalConfirmationCode.PROPOSAL_SUPERSEDED,
                    "This proposal was replaced by a newer one. Please confirm the latest proposal.");
        }
        if (proposal.status() == ProposalStatus.CONSUMED) {
            return reconcileConsumed(proposal, context);
        }
        if (proposal.status() != ProposalStatus.ACTIVE) {
            return failure(ProposalConfirmationCode.STALE_PROPOSAL,
                    "This proposal is no longer valid. Please request fresh seats.");
        }
        if (proposal.seatIds().size() < 1 || proposal.seatIds().size() > 10) {
            return failure(ProposalConfirmationCode.STALE_PROPOSAL,
                    "This proposal is no longer valid. Please request fresh seats.");
        }

        // Live revalidation (stale-prone by design; no write until it passes).
        Revalidation revalidation = revalidateLive(proposal, context, now);
        if (revalidation instanceof Revalidation.Failed failed) {
            return failure(failed.code(), failed.message());
        }
        Revalidation.Ok ok = (Revalidation.Ok) revalidation;

        try {
            ReservationServiceReservationDto created = reservationServiceClient.createReservation(
                    proposal.eventSessionId(), proposal.seatIds(), ok.seatPrices(),
                    proposal.serverIdempotencyKey(), context);
            proposalStore.markConsumed(proposalId, created.id());
            log.info("AI confirmed reservation created: proposalId={}, reservationId={}, seats={}",
                    proposalId, created.id(), proposal.seatIds().size());
            return new ConfirmationOutcome.Success(toCard(proposal, created));
        } catch (ConflictException ex) {
            log.warn("AI confirmed reservation conflicted: proposalId={}", proposalId);
            return failure(ProposalConfirmationCode.RESERVATION_CONFLICT,
                    "Those seats were just taken. Please request fresh seats — no alternative was booked.");
        } catch (ReservationServiceUnavailableException ex) {
            if (ex.getError() == AiToolError.DOWNSTREAM_TIMEOUT) {
                log.warn("AI confirmed reservation timed out after submit (ambiguous): proposalId={}", proposalId);
                return failure(ProposalConfirmationCode.RESERVATION_RESULT_UNKNOWN_RETRY_SAFE,
                        "The reservation result is unknown after a timeout. Retry the same confirmation — it is safe and will not double-book.");
            }
            log.warn("AI confirmed reservation unavailable: proposalId={}", proposalId);
            return failure(ProposalConfirmationCode.RESERVATION_SERVICE_UNAVAILABLE,
                    "Reservation service is temporarily unavailable. Please try again shortly.");
        } catch (AiToolException ex) {
            if (ex.getError() == AiToolError.UNAUTHENTICATED || ex.getError() == AiToolError.FORBIDDEN) {
                throw ex;
            }
            log.warn("AI confirmed reservation rejected: proposalId={}, error={}", proposalId, ex.getError());
            return failure(ProposalConfirmationCode.STALE_PROPOSAL,
                    "This proposal is no longer valid. Please request fresh seats.");
        }
    }

    private ConfirmationOutcome reconcileConsumed(ReservationProposal proposal, AiRequestContext context) {
        if (proposal.reservationId() == null) {
            return failure(ProposalConfirmationCode.PROPOSAL_ALREADY_CONSUMED,
                    "This proposal was already used. Please request fresh seats for a new hold.");
        }
        try {
            ReservationServiceReservationDto current =
                    reservationServiceClient.getReservation(proposal.reservationId(), context);
            log.info("AI duplicate confirm reconciled to same reservation: proposalId={}, reservationId={}",
                    proposal.proposalId(), proposal.reservationId());
            return new ConfirmationOutcome.Success(toCard(proposal, current));
        } catch (ReservationServiceUnavailableException ex) {
            return failure(ProposalConfirmationCode.RESERVATION_SERVICE_UNAVAILABLE,
                    "Reservation service is temporarily unavailable. Please check My Tickets shortly.");
        } catch (AiToolException ex) {
            if (ex.getError() == AiToolError.UNAUTHENTICATED || ex.getError() == AiToolError.FORBIDDEN) {
                throw ex;
            }
            return failure(ProposalConfirmationCode.PROPOSAL_ALREADY_CONSUMED,
                    "This proposal was already used. Please check My Tickets for the existing hold.");
        }
    }

    private Revalidation revalidateLive(ReservationProposal proposal, AiRequestContext context, Instant now) {
        SessionBookingContextClientDto booking;
        try {
            booking = eventServiceClient.getSessionBookingContext(proposal.eventSessionId(), context);
        } catch (AiToolException ex) {
            if (ex.getError() == AiToolError.INVALID_TOOL_ARGUMENT
                    || ex.getError() == AiToolError.NOT_FOUND) {
                return new Revalidation.Failed(ProposalConfirmationCode.SESSION_NOT_BOOKABLE,
                        "This session is no longer bookable. Please choose another session.");
            }
            if (ex.getError() == AiToolError.UNAUTHENTICATED || ex.getError() == AiToolError.FORBIDDEN) {
                throw ex;
            }
            return new Revalidation.Failed(ProposalConfirmationCode.RESERVATION_SERVICE_UNAVAILABLE,
                    "Session lookup is temporarily unavailable. Please try again shortly.");
        } catch (RuntimeException ex) {
            return new Revalidation.Failed(ProposalConfirmationCode.RESERVATION_SERVICE_UNAVAILABLE,
                    "Session lookup is temporarily unavailable. Please try again shortly.");
        }
        if (booking == null || booking.eventSessionId() == null
                || !proposal.eventSessionId().equals(booking.eventSessionId())) {
            return new Revalidation.Failed(ProposalConfirmationCode.STALE_PROPOSAL,
                    "This proposal is no longer valid. Please request fresh seats.");
        }
        if (proposal.eventId() != null && booking.eventId() != null
                && !proposal.eventId().equals(booking.eventId())) {
            return new Revalidation.Failed(ProposalConfirmationCode.STALE_PROPOSAL,
                    "This proposal is no longer valid. Please request fresh seats.");
        }
        if (!"SCHEDULED".equalsIgnoreCase(booking.sessionStatus())) {
            return new Revalidation.Failed(ProposalConfirmationCode.SESSION_NOT_BOOKABLE,
                    "This session is no longer bookable. Please choose another session.");
        }
        if (booking.saleStartsAt() != null && now.isBefore(booking.saleStartsAt())) {
            return new Revalidation.Failed(ProposalConfirmationCode.SESSION_NOT_BOOKABLE,
                    "Ticket sales for this session have not opened yet.");
        }
        if (booking.saleEndsAt() != null && now.isAfter(booking.saleEndsAt())) {
            return new Revalidation.Failed(ProposalConfirmationCode.SESSION_NOT_BOOKABLE,
                    "Ticket sales for this session have closed.");
        }
        if (booking.startsAt() == null || !booking.startsAt().isAfter(now)) {
            return new Revalidation.Failed(ProposalConfirmationCode.SESSION_NOT_BOOKABLE,
                    "This session is no longer bookable. Please choose another session.");
        }

        SeatCandidateAssembler.AssembledSnapshot assembled;
        try {
            assembled = seatAssembler.assemble(
                    proposal.eventSessionId(), null, proposal.preferredCategory(),
                    proposal.currency(), context);
        } catch (AiToolException ex) {
            if (ex.getError() == AiToolError.INVALID_TOOL_ARGUMENT
                    || ex.getError() == AiToolError.NOT_FOUND) {
                return new Revalidation.Failed(ProposalConfirmationCode.STALE_PROPOSAL,
                        "This proposal is no longer valid. Please request fresh seats.");
            }
            if (ex.getError() == AiToolError.UNAUTHENTICATED || ex.getError() == AiToolError.FORBIDDEN) {
                throw ex;
            }
            return new Revalidation.Failed(ProposalConfirmationCode.RESERVATION_SERVICE_UNAVAILABLE,
                    "Seat availability is temporarily unavailable. Please try again shortly.");
        } catch (RuntimeException ex) {
            return new Revalidation.Failed(ProposalConfirmationCode.RESERVATION_SERVICE_UNAVAILABLE,
                    "Seat availability is temporarily unavailable. Please try again shortly.");
        }

        Map<UUID, PricedSeat> liveBySeat = assembled.snapshot().seats().stream()
                .collect(Collectors.toMap(PricedSeat::seatId, Function.identity(),
                        (first, second) -> first));
        for (UUID seatId : proposal.seatIds()) {
            if (!liveBySeat.containsKey(seatId)) {
                return new Revalidation.Failed(ProposalConfirmationCode.SEATS_NO_LONGER_AVAILABLE,
                        "One or more seats are no longer available. Please request fresh seats — no alternative was booked.");
            }
        }
        long recomputed = 0L;
        for (UUID seatId : proposal.seatIds()) {
            PricedSeat live = liveBySeat.get(seatId);
            if (live.currency() == null || !live.currency().equalsIgnoreCase(proposal.currency())) {
                return new Revalidation.Failed(ProposalConfirmationCode.PRICE_CHANGED,
                        "The price changed. Please request a fresh proposal — the new price was not accepted.");
            }
            if (proposal.preferredCategory() != null
                    && (live.categoryName() == null
                            || !live.categoryName().trim().equalsIgnoreCase(
                                    proposal.preferredCategory().trim()))) {
                return new Revalidation.Failed(ProposalConfirmationCode.PRICE_CHANGED,
                        "The price changed. Please request a fresh proposal — the new price was not accepted.");
            }
            recomputed += live.priceMinor();
        }
        if (recomputed != proposal.totalPriceMinor()) {
            return new Revalidation.Failed(ProposalConfirmationCode.PRICE_CHANGED,
                    "The price changed. Please request a fresh proposal — the new price was not accepted.");
        }
        if (proposal.maxTotalPriceMinor() != null && recomputed > proposal.maxTotalPriceMinor()) {
            return new Revalidation.Failed(ProposalConfirmationCode.PRICE_CHANGED,
                    "The price changed. Please request a fresh proposal — the new price was not accepted.");
        }
        if (proposal.pricingTierIds() != null && proposal.pricingTierIds().size() == proposal.seatIds().size()) {
            for (int i = 0; i < proposal.seatIds().size(); i++) {
                UUID expectedTier = proposal.pricingTierIds().get(i);
                UUID liveTier = liveBySeat.get(proposal.seatIds().get(i)).pricingTierId();
                if (expectedTier != null && liveTier != null && !expectedTier.equals(liveTier)) {
                    return new Revalidation.Failed(ProposalConfirmationCode.PRICE_CHANGED,
                            "The price changed. Please request a fresh proposal — the new price was not accepted.");
                }
            }
        }

        List<BigDecimal> seatPrices = proposal.seatIds().stream()
                .map(seatId -> BigDecimal.valueOf(liveBySeat.get(seatId).priceMinor(), 2))
                .toList();
        return new Revalidation.Ok(seatPrices);
    }

    private ReservationCreatedCard toCard(
            ReservationProposal proposal, ReservationServiceReservationDto created) {
        Map<UUID, String> labels = proposal.seatDisplays() == null ? Map.of()
                : proposal.seatDisplays().stream()
                        .filter(display -> display != null && display.seatId() != null)
                        .collect(Collectors.toMap(
                                ReservationProposal.SeatDisplay::seatId,
                                display -> display.label() == null ? "" : display.label(),
                                (first, second) -> first));
        List<String> seatLabels = proposal.seatIds().stream()
                .map(seatId -> labels.getOrDefault(seatId, "Seat " + seatId))
                .toList();
        return new ReservationCreatedCard(
                created.id(), created.eventSessionId(), List.copyOf(proposal.seatIds()), seatLabels,
                created.totalAmount(), proposal.currency(), created.status(),
                created.expiresAt(), "/checkout/" + created.id());
    }

    private ConfirmationOutcome.Failure failure(ProposalConfirmationCode code, String message) {
        return new ConfirmationOutcome.Failure(code, message);
    }

    private void requireAuthenticated(AiRequestContext context, String ownerSubject) {
        if (context == null || !context.isAuthenticated()) {
            throw new AiToolException(AiToolError.UNAUTHENTICATED,
                    "Authentication is required to confirm a reservation.");
        }
        if (ownerSubject == null || ownerSubject.isBlank()
                || !ownerSubject.equals(context.userId())) {
            throw new AiToolException(AiToolError.FORBIDDEN,
                    "You are not allowed to confirm this proposal with these credentials.");
        }
    }

    private sealed interface Revalidation permits Revalidation.Ok, Revalidation.Failed {
        record Ok(List<BigDecimal> seatPrices) implements Revalidation {}
        record Failed(ProposalConfirmationCode code, String message) implements Revalidation {}
    }
}
