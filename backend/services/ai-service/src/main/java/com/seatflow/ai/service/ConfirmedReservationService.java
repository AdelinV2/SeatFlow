package com.seatflow.ai.service;

import com.seatflow.ai.api.dto.ReservationCreatedCard;
import com.seatflow.ai.context.AiRequestContext;

import java.util.UUID;

/**
 * Explicit-confirmation boundary for reservation creation (TASK-P15-005 sections 4, 6-11).
 *
 * <p>The only state-changing AI capability: creation of a normal SeatFlow reservation hold after
 * the authenticated user explicitly confirms one exact server-side proposal via
 * {@code POST /api/ai/proposals/{proposalId}/confirm} with an empty body. Chat text such as
 * {@code yes} and model tool calls can never trigger this service — only the dedicated endpoint
 * action authorizes the write.
 */
public interface ConfirmedReservationService {

    /**
     * Confirms one exact server-side proposal after owner/status/TTL validation and live
     * availability + price revalidation. Never substitutes seats or auto-accepts a new price:
     * changed seats/price require a fresh proposal and fresh confirmation.
     */
    ConfirmationOutcome confirmProposal(
            UUID proposalId, String ownerSubject, AiRequestContext context);

    /** Closed outcome hierarchy: exactly one reservation call on success, or a stable failure. */
    sealed interface ConfirmationOutcome
            permits ConfirmationOutcome.Success, ConfirmationOutcome.Failure {

        record Success(ReservationCreatedCard card) implements ConfirmationOutcome {}

        record Failure(ProposalConfirmationCode code, String message) implements ConfirmationOutcome {}
    }
}
