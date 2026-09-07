package com.seatflow.ai.api;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.context.AiRequestContextFactory;
import com.seatflow.ai.api.dto.ProposalConfirmationError;
import com.seatflow.ai.api.dto.ReservationCreatedCard;
import com.seatflow.ai.service.ConfirmedReservationService;
import com.seatflow.ai.service.ProposalConfirmationCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Confirmation endpoint tests (TASK-P15-005 sections 6, 10; mandatory 8).
 */
@ExtendWith(MockitoExtension.class)
class ProposalConfirmationControllerTest {

    @Mock
    private ConfirmedReservationService confirmedReservations;
    @Mock
    private AiRequestContextFactory requestContexts;

    private ProposalConfirmationController controller() {
        return new ProposalConfirmationController(confirmedReservations, requestContexts);
    }

    private AiRequestContext context() {
        return new AiRequestContext("bearer", "corr", "owner-1");
    }

    @Test
    @DisplayName("8: non-empty confirmation body is rejected (no client-editable seats/price)")
    void nonEmptyBodyRejected() {
        var controller = controller();
        UUID proposalId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.confirm(
                proposalId, Map.of("seatIds", List.of(UUID.randomUUID().toString()))))
                .isInstanceOf(com.seatflow.common.domain.exception.ValidationException.class);
    }

    @Test
    @DisplayName("success returns 201 with the authoritative card")
    void successReturnsCard() {
        when(requestContexts.requireAuthenticated()).thenReturn(context());
        UUID proposalId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        var card = new ReservationCreatedCard(reservationId, UUID.randomUUID(),
                List.of(UUID.randomUUID()), List.of("Orchestra Row A Seat 1"),
                new BigDecimal("15.00"), "EUR", "PENDING",
                Instant.parse("2026-09-07T10:15:00Z"), "/checkout/" + reservationId);
        when(confirmedReservations.confirmProposal(eq(proposalId), eq("owner-1"), any()))
                .thenReturn(new ConfirmedReservationService.ConfirmationOutcome.Success(card));

        var response = controller().confirm(proposalId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(card);
    }

    @Test
    @DisplayName("unknown proposal maps to 404 with a safe code")
    void unknownMapsTo404() {
        when(requestContexts.requireAuthenticated()).thenReturn(context());
        UUID proposalId = UUID.randomUUID();
        when(confirmedReservations.confirmProposal(eq(proposalId), eq("owner-1"), any()))
                .thenReturn(new ConfirmedReservationService.ConfirmationOutcome.Failure(
                        ProposalConfirmationCode.PROPOSAL_NOT_FOUND, "not found"));

        var response = controller().confirm(proposalId, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var error = (ProposalConfirmationError) response.getBody();
        assertThat(error.code()).isEqualTo("PROPOSAL_NOT_FOUND");
    }
}
