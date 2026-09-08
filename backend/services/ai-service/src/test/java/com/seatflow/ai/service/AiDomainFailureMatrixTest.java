package com.seatflow.ai.service;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.ReservationServiceClient;
import com.seatflow.ai.client.dto.ReservationServiceReservationDto;
import com.seatflow.ai.client.dto.SessionBookingContextClientDto;
import com.seatflow.ai.client.exception.ReservationServiceUnavailableException;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.exception.AiToolError;
import com.seatflow.ai.exception.AiToolException;
import com.seatflow.ai.orchestration.AssistantConversationProperties;
import com.seatflow.ai.orchestration.ConversationStore;
import com.seatflow.ai.orchestration.MutableClock;
import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposal;
import com.seatflow.ai.proposal.ReservationProposalProperties;
import com.seatflow.ai.service.impl.ConfirmedReservationServiceImpl;
import com.seatflow.ai.service.seat.PricedSeat;
import com.seatflow.ai.service.seat.SeatCandidateAssembler;
import com.seatflow.ai.service.seat.SeatSnapshot;
import com.seatflow.common.domain.exception.ConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain failure matrix tests (TASK-P15-007 section 6): stale downstream data and reservation
 * races never fabricate a successful reservation.
 */
@ExtendWith(MockitoExtension.class)
class AiDomainFailureMatrixTest {

    private MutableClock clock;
    private ProposalStore proposals;
    private ConfirmedReservationServiceImpl service;

    @Mock
    private EventServiceClient eventServiceClient;
    @Mock
    private SeatCandidateAssembler assembler;
    @Mock
    private ReservationServiceClient reservationClient;

    private final AiRequestContext context =
            new AiRequestContext("bearer-owner-jwt", "corr-1", "owner-1");

    private UUID conversationId;
    private UUID sessionId;
    private UUID eventId;
    private UUID seatA;
    private UUID seatB;
    private UUID tierA;
    private UUID tierB;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-07T10:00:00Z"));
        proposals = new ProposalStore(
                new ReservationProposalProperties(Duration.ofMinutes(5), 500, Duration.ofMinutes(1)), clock);
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        var conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        service = new ConfirmedReservationServiceImpl(proposals, conversations,
                eventServiceClient, assembler, reservationClient,
                new AiMetrics(new SimpleMeterRegistry()), clock);
        conversationId = conversations.create("owner-1").conversationId();
        sessionId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        seatA = UUID.randomUUID();
        seatB = UUID.randomUUID();
        tierA = UUID.randomUUID();
        tierB = UUID.randomUUID();
    }

    private ReservationProposal proposal(long totalMinor) {
        return proposals.create(conversationId, "owner-1", eventId, sessionId,
                List.of(seatA, seatB),
                List.of(new ReservationProposal.SeatDisplay(seatA, "Row A Seat 1", "Orchestra", "A", 1),
                        new ReservationProposal.SeatDisplay(seatB, "Row A Seat 2", "Orchestra", "A", 2)),
                List.of(tierA, tierB), null, null, null, totalMinor, "EUR");
    }

    private SessionBookingContextClientDto bookable() {
        return new SessionBookingContextClientDto(sessionId, eventId, "PUBLISHED", "SCHEDULED",
                clock.instant().plus(Duration.ofDays(3)),
                clock.instant().plus(Duration.ofDays(3)).plus(java.time.Duration.ofHours(2)), null, null, UUID.randomUUID());
    }

    private SeatCandidateAssembler.AssembledSnapshot snapshot(long priceA, long priceB,
                                                             String currencyA, String currencyB,
                                                             UUID liveTierA, UUID liveTierB,
                                                             boolean includeB) {
        var seats = includeB
                ? List.of(priced(seatA, priceA, currencyA, liveTierA, 1),
                        priced(seatB, priceB, currencyB, liveTierB, 2))
                : List.of(priced(seatA, priceA, currencyA, liveTierA, 1));
        return new SeatCandidateAssembler.AssembledSnapshot(
                new SeatSnapshot(sessionId, clock.instant(), seats,
                        Optional.empty(), Optional.empty(), true),
                List.of());
    }

    private PricedSeat priced(UUID seat, long price, String currency, UUID tier, int number) {
        return new PricedSeat(seat, UUID.randomUUID(), "Orchestra", "A", number,
                Optional.empty(), "STD", tier, price, currency);
    }

    private ReservationServiceReservationDto created(UUID reservationId) {
        return new ReservationServiceReservationDto(reservationId, sessionId, eventId, "PENDING",
                clock.instant().plus(Duration.ofMinutes(15)), new BigDecimal("30.00"), 2,
                clock.instant().plus(Duration.ofDays(3)), null, null, clock.instant(), List.of());
    }

    private ProposalConfirmationCode codeOf(ConfirmedReservationService.ConfirmationOutcome outcome) {
        return ((ConfirmedReservationService.ConfirmationOutcome.Failure) outcome).code();
    }

    @Test
    @DisplayName("event service timeout during revalidation -> SERVICE_UNAVAILABLE, no write")
    void eventTimeoutUnavailable() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any()))
                .thenThrow(new ReservationServiceUnavailableException(
                        AiToolError.DOWNSTREAM_TIMEOUT, "event lookup timed out"));

        assertThat(codeOf(service.confirmProposal(p.proposalId(), "owner-1", context)))
                .isEqualTo(ProposalConfirmationCode.RESERVATION_SERVICE_UNAVAILABLE);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("event deleted/not found during revalidation -> SESSION_NOT_BOOKABLE, no write")
    void eventNotFoundNotBookable() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any()))
                .thenThrow(new AiToolException(AiToolError.NOT_FOUND, "event gone"));

        assertThat(codeOf(service.confirmProposal(p.proposalId(), "owner-1", context)))
                .isEqualTo(ProposalConfirmationCode.SESSION_NOT_BOOKABLE);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("session cancelled during conversation -> SESSION_NOT_BOOKABLE, no write")
    void sessionCancelledNotBookable() {
        ReservationProposal p = proposal(3000L);
        var cancelled = new SessionBookingContextClientDto(sessionId, eventId, "PUBLISHED", "CANCELLED",
                clock.instant().plus(Duration.ofDays(3)),
                clock.instant().plus(Duration.ofDays(3)).plus(java.time.Duration.ofHours(2)), null, null, UUID.randomUUID());
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(cancelled);

        assertThat(codeOf(service.confirmProposal(p.proposalId(), "owner-1", context)))
                .isEqualTo(ProposalConfirmationCode.SESSION_NOT_BOOKABLE);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("seat held after proposal (snapshot disagree) -> no substitution, stale failure")
    void seatHeldAfterProposal() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(bookable());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(snapshot(1500L, 1500L, "EUR", "EUR", tierA, tierB, false));

        assertThat(codeOf(service.confirmProposal(p.proposalId(), "owner-1", context)))
                .isEqualTo(ProposalConfirmationCode.SEATS_NO_LONGER_AVAILABLE);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pricing tier disappears -> PRICE_CHANGED, fresh proposal required")
    void pricingTierGone() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(bookable());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(snapshot(1500L, 1500L, "EUR", "EUR",
                        UUID.randomUUID(), UUID.randomUUID(), true));

        assertThat(codeOf(service.confirmProposal(p.proposalId(), "owner-1", context)))
                .isEqualTo(ProposalConfirmationCode.PRICE_CHANGED);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("requested currency unavailable live -> PRICE_CHANGED, never a currency guess")
    void currencyUnavailable() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(bookable());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(snapshot(1500L, 1500L, "EUR", "USD", tierA, tierB, true));

        assertThat(codeOf(service.confirmProposal(p.proposalId(), "owner-1", context)))
                .isEqualTo(ProposalConfirmationCode.PRICE_CHANGED);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("409 double-booking conflict -> RESERVATION_CONFLICT, exactly one attempt")
    void conflictMaps() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(bookable());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(snapshot(1500L, 1500L, "EUR", "EUR", tierA, tierB, true));
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("seat taken",
                        com.seatflow.common.domain.enums.ErrorCode.SEAT_ALREADY_RESERVED));

        assertThat(codeOf(service.confirmProposal(p.proposalId(), "owner-1", context)))
                .isEqualTo(ProposalConfirmationCode.RESERVATION_CONFLICT);
        verify(reservationClient, times(1)).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reservation 5xx before accept -> SERVICE_UNAVAILABLE, no success claimed")
    void reservation5xxBeforeAccept() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(bookable());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(snapshot(1500L, 1500L, "EUR", "EUR", tierA, tierB, true));
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenThrow(new ReservationServiceUnavailableException(
                        AiToolError.DOWNSTREAM_UNAVAILABLE, "reservation 500"));

        var outcome = service.confirmProposal(p.proposalId(), "owner-1", context);
        assertThat(codeOf(outcome)).isEqualTo(ProposalConfirmationCode.RESERVATION_SERVICE_UNAVAILABLE);
        assertThat(outcome).isNotInstanceOf(
                ConfirmedReservationService.ConfirmationOutcome.Success.class);
    }

    @Test
    @DisplayName("timeout after submit is ambiguous -> 202 retry-safe, never success")
    void timeoutAfterSubmitRetrySafe() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(bookable());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(snapshot(1500L, 1500L, "EUR", "EUR", tierA, tierB, true));
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenThrow(new ReservationServiceUnavailableException(
                        AiToolError.DOWNSTREAM_TIMEOUT, "submit timed out"));

        var outcome = service.confirmProposal(p.proposalId(), "owner-1", context);
        assertThat(codeOf(outcome))
                .isEqualTo(ProposalConfirmationCode.RESERVATION_RESULT_UNKNOWN_RETRY_SAFE);
        assertThat(ProposalConfirmationCode.RESERVATION_RESULT_UNKNOWN_RETRY_SAFE.httpStatus())
                .isEqualTo(202);
    }

    @Test
    @DisplayName("duplicate browser confirm after success reconciles to the same reservation")
    void duplicateBrowserConfirmReconciles() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(bookable());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(snapshot(1500L, 1500L, "EUR", "EUR", tierA, tierB, true));
        UUID reservationId = UUID.randomUUID();
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenReturn(created(reservationId));
        when(reservationClient.getReservation(eq(reservationId), any()))
                .thenReturn(created(reservationId));

        var first = service.confirmProposal(p.proposalId(), "owner-1", context);
        var second = service.confirmProposal(p.proposalId(), "owner-1", context);

        assertThat(first).isInstanceOf(ConfirmedReservationService.ConfirmationOutcome.Success.class);
        assertThat(second).isInstanceOf(ConfirmedReservationService.ConfirmationOutcome.Success.class);
        assertThat(((ConfirmedReservationService.ConfirmationOutcome.Success) second).card().reservationId())
                .isEqualTo(reservationId);
        verify(reservationClient, times(1)).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("duplicate network retry reuses the server idempotency key")
    void duplicateNetworkRetrySameKey() {
        ReservationProposal p = proposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(bookable());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(snapshot(1500L, 1500L, "EUR", "EUR", tierA, tierB, true));
        UUID reservationId = UUID.randomUUID();
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenReturn(created(reservationId));
        when(reservationClient.getReservation(eq(reservationId), any()))
                .thenReturn(created(reservationId));

        service.confirmProposal(p.proposalId(), "owner-1", context);
        service.confirmProposal(p.proposalId(), "owner-1", context);

        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reservationClient, times(1)).createReservation(any(), any(), any(), keys.capture(), any());
        assertThat(keys.getValue()).isEqualTo(p.serverIdempotencyKey());
    }
}
