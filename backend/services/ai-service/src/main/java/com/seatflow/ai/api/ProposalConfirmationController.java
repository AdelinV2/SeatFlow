package com.seatflow.ai.api;

import com.seatflow.ai.api.dto.ProposalConfirmationError;
import com.seatflow.ai.api.dto.ReservationCreatedCard;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.service.ConfirmedReservationService;
import com.seatflow.common.domain.dto.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Explicit-confirmation boundary for AI reservation creation (TASK-P15-005 sections 4, 6).
 *
 * <p>The only state-changing AI capability. The confirmation request body must stay empty:
 * exact seat/session/price values always come from server-side proposal storage, never from
 * client-editable fields. A chat message such as {@code yes} or a model tool-call attempt can
 * never reach this service — only this dedicated endpoint action authorizes the write.
 */
@RestController
@RequestMapping("/api/ai/proposals")
@RequiredArgsConstructor
@Tag(name = "AI Assistant", description = "Explicit reservation confirmation (owner-bound)")
public class ProposalConfirmationController {

    private final ConfirmedReservationService confirmedReservations;
    private final AiRequestContextFactory requestContexts;

    @PostMapping("/{proposalId}/confirm")
    @Operation(summary = "Confirm one exact server-side proposal",
            description = "Validates ownership/status/TTL, revalidates live availability and price, "
                    + "then creates exactly one normal 15-minute hold. The request body must be empty; "
                    + "seat IDs and prices are never accepted from the client.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reservation hold created",
                    content = @Content(schema = @Schema(implementation = ReservationCreatedCard.class))),
            @ApiResponse(responseCode = "202", description = "Result unknown after timeout; retry is safe",
                    content = @Content(schema = @Schema(implementation = ProposalConfirmationError.class))),
            @ApiResponse(responseCode = "400", description = "Non-empty confirmation body",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Unknown proposal (or cross-owner ID)",
                    content = @Content(schema = @Schema(implementation = ProposalConfirmationError.class))),
            @ApiResponse(responseCode = "409", description = "Stale/superseded/consumed proposal or revalidation failure",
                    content = @Content(schema = @Schema(implementation = ProposalConfirmationError.class))),
            @ApiResponse(responseCode = "410", description = "Proposal expired",
                    content = @Content(schema = @Schema(implementation = ProposalConfirmationError.class))),
            @ApiResponse(responseCode = "503", description = "Reservation service unavailable",
                    content = @Content(schema = @Schema(implementation = ProposalConfirmationError.class)))
    })
    public ResponseEntity<?> confirm(
            @PathVariable UUID proposalId,
            @RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            throw new com.seatflow.common.domain.exception.ValidationException(
                    "Confirmation body must be empty; seats and price come from the stored proposal",
                    com.seatflow.common.domain.enums.ErrorCode.INVALID_REQUEST);
        }
        AiRequestContext context = requestContexts.requireAuthenticated();
        ConfirmedReservationService.ConfirmationOutcome outcome =
                confirmedReservations.confirmProposal(proposalId, context.userId(), context);
        return switch (outcome) {
            case ConfirmedReservationService.ConfirmationOutcome.Success success ->
                    ResponseEntity.status(201).body(success.card());
            case ConfirmedReservationService.ConfirmationOutcome.Failure failure ->
                    ResponseEntity.status(failure.code().httpStatus())
                            .body(new ProposalConfirmationError(
                                    failure.code().name(), failure.message()));
        };
    }
}
