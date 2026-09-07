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
import com.seatflow.ai.orchestration.AssistantOrchestrator;
import com.seatflow.ai.orchestration.ConversationStore;
import com.seatflow.ai.proposal.ProposalStatus;
import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposal;
import com.seatflow.ai.proposal.ReservationProposalProperties;
import com.seatflow.ai.service.impl.ConfirmedReservationServiceImpl;
import com.seatflow.ai.service.seat.PricedSeat;
import com.seatflow.ai.service.seat.SeatCandidateAssembler;
import com.seatflow.ai.service.seat.SeatSnapshot;
import com.seatflow.common.domain.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirmation boundary tests (TASK-P15-005 sections 6-11, 13; mandatory 4-6, 8-15, 17, 19).
 */
@ExtendWith(MockitoExtension.class)
class ConfirmedReservationServiceTest {

    private MutableClock clock;
    private ProposalStore proposals;
    private ConversationStore conversations;

    @Mock
    private EventServiceClient eventServiceClient;
    @Mock
    private SeatCandidateAssembler assembler;
    @Mock
    private ReservationServiceClient reservationClient;

    private ConfirmedReservationService service;

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
                new ReservationProposalProperties(
                        Duration.ofMinutes(5), 500, Duration.ofMinutes(1)),
                clock);
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        service = new ConfirmedReservationServiceImpl(
                proposals, conversations, eventServiceClient, assembler, reservationClient, clock);

        conversationId = conversations.create("owner-1").conversationId();
        sessionId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        seatA = UUID.randomUUID();
        seatB = UUID.randomUUID();
        tierA = UUID.randomUUID();
        tierB = UUID.randomUUID();
    }

    private ReservationProposal activeProposal(long totalMinor) {
        return proposals.create(conversationId, "owner-1", eventId, sessionId,
                List.of(seatA, seatB),
                List.of(
                        new ReservationProposal.SeatDisplay(seatA, "Orchestra Row A Seat 1", "Orchestra", "A", 1),
                        new ReservationProposal.SeatDisplay(seatB, "Orchestra Row A Seat 2", "Orchestra", "A", 2)),
                List.of(tierA, tierB), null, null, null, totalMinor, "EUR");
    }

    private SessionBookingContextClientDto bookableContext() {
        return new SessionBookingContextClientDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                clock.instant().plus(Duration.ofDays(3)), clock.instant().plus(Duration.ofDays(3)).plus(Duration.ofHours(2)),
                null, null, UUID.randomUUID());
    }

    private SeatCandidateAssembler.AssembledSnapshot liveSnapshot(long priceAMinor, long priceBMinor) {
        var seats = List.of(
                new PricedSeat(seatA, UUID.randomUUID(), "Orchestra", "A", 1, Optional.empty(),
                        "STD", tierA, priceAMinor, "EUR"),
                new PricedSeat(seatB, UUID.randomUUID(), "Orchestra", "A", 2, Optional.empty(),
                        "STD", tierB, priceBMinor, "EUR"));
        return new SeatCandidateAssembler.AssembledSnapshot(
                new SeatSnapshot(sessionId, clock.instant(), seats,
                        Optional.empty(), Optional.empty(), true),
                List.of());
    }

    private ReservationServiceReservationDto createdDto(UUID reservationId, Instant expiresAt) {
        return new ReservationServiceReservationDto(
                reservationId, sessionId, eventId, "PENDING", expiresAt,
                new BigDecimal("30.00"), 2,
                clock.instant().plus(Duration.ofDays(3)), null, null,
                clock.instant(),
                List.of(
                        new ReservationServiceReservationDto.ReservationServiceSeatDto(
                                seatA, "HELD", new BigDecimal("15.00"), "A", 1, tierA, "STD"),
                        new ReservationServiceReservationDto.ReservationServiceSeatDto(
                                seatB, "HELD", new BigDecimal("15.00"), "A", 2, tierB, "STD")));
    }

    private void stubLiveOk(long priceAMinor, long priceBMinor) {
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any()))
                .thenReturn(bookableContext());
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(liveSnapshot(priceAMinor, priceBMinor));
    }

    @Test
    @DisplayName("4: valid owner + active + unchanged state -> exactly one reservation call")
    void validConfirmCreatesExactlyOneHold() {
        ReservationProposal proposal = activeProposal(3000L);
        stubLiveOk(1500L, 1500L);
        UUID reservationId = UUID.randomUUID();
        Instant authoritativeExpiry = clock.instant().plus(Duration.ofMinutes(15));
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenReturn(createdDto(reservationId, authoritativeExpiry));

        var outcome = service.confirmProposal(proposal.proposalId(), "owner-1", context);

        assertThat(outcome).isInstanceOf(ConfirmedReservationService.ConfirmationOutcome.Success.class);
        var card = ((ConfirmedReservationService.ConfirmationOutcome.Success) outcome).card();
        assertThat(card.reservationId()).isEqualTo(reservationId);
        // 14: authoritative expiry, never now+15m computed locally.
        assertThat(card.expiresAt()).isEqualTo(authoritativeExpiry);
        assertThat(card.checkoutRoute()).isEqualTo("/checkout/" + reservationId);
        assertThat(card.seatIds()).containsExactly(seatA, seatB);
        verify(reservationClient, times(1)).createReservation(any(), any(), any(), any(), any());
        assertThat(proposals.peekForOwner(proposal.proposalId(), "owner-1").status())
                .isEqualTo(ProposalStatus.CONSUMED);
    }

    @Test
    @DisplayName("8: confirm uses only server-stored seats/prices (no client-editable fields)")
    void confirmUsesServerStoredValues() {
        ReservationProposal proposal = activeProposal(3000L);
        stubLiveOk(1500L, 1500L);
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenReturn(createdDto(UUID.randomUUID(), clock.instant().plus(Duration.ofMinutes(15))));

        service.confirmProposal(proposal.proposalId(), "owner-1", context);

        ArgumentCaptor<List<UUID>> seats = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<BigDecimal>> prices = ArgumentCaptor.forClass(List.class);
        verify(reservationClient).createReservation(
                eq(sessionId), seats.capture(), prices.capture(), eq(proposal.serverIdempotencyKey()), any());
        assertThat(seats.getValue()).containsExactly(seatA, seatB);
        assertThat(prices.getValue()).containsExactly(
                new BigDecimal("15.00"), new BigDecimal("15.00"));
    }

    @Test
    @DisplayName("5: another user cannot confirm the proposal")
    void crossOwnerCannotConfirm() {
        ReservationProposal proposal = activeProposal(3000L);
        var other = new AiRequestContext("bearer-other", "corr-2", "owner-2");

        var outcome = service.confirmProposal(proposal.proposalId(), "owner-2", other);

        assertThat(outcome).isInstanceOf(ConfirmedReservationService.ConfirmationOutcome.Failure.class);
        var failure = (ConfirmedReservationService.ConfirmationOutcome.Failure) outcome;
        assertThat(failure.code()).isIn(
                ProposalConfirmationCode.PROPOSAL_NOT_FOUND, ProposalConfirmationCode.PROPOSAL_FORBIDDEN);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("6: expired/superseded/consumed proposals never write downstream")
    void terminalStatesNeverWrite() {
        // Expired.
        ReservationProposal expired = activeProposal(3000L);
        clock.advance(Duration.ofMinutes(6));
        var expiredOutcome = service.confirmProposal(expired.proposalId(), "owner-1", context);
        assertThat(((ConfirmedReservationService.ConfirmationOutcome.Failure) expiredOutcome).code())
                .isEqualTo(ProposalConfirmationCode.PROPOSAL_EXPIRED);

        // Superseded (new proposal for the same conversation).
        clock.advance(Duration.ofMinutes(-6));
        ReservationProposal first = activeProposal(3000L);
        activeProposal(3000L);
        var supersededOutcome = service.confirmProposal(first.proposalId(), "owner-1", context);
        assertThat(((ConfirmedReservationService.ConfirmationOutcome.Failure) supersededOutcome).code())
                .isEqualTo(ProposalConfirmationCode.PROPOSAL_SUPERSEDED);

        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("9: one seat unavailable -> conflict, no substitution, no write")
    void seatUnavailableNoSubstitution() {
        ReservationProposal proposal = activeProposal(3000L);
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any()))
                .thenReturn(bookableContext());
        // Live snapshot only contains seatA now.
        var seats = List.of(new PricedSeat(seatA, UUID.randomUUID(), "Orchestra", "A", 1,
                Optional.empty(), "STD", tierA, 1500L, "EUR"));
        when(assembler.assemble(eq(sessionId), eq(null), any(), eq("EUR"), any()))
                .thenReturn(new SeatCandidateAssembler.AssembledSnapshot(
                        new SeatSnapshot(sessionId, clock.instant(), seats,
                                Optional.empty(), Optional.empty(), true),
                        List.of()));

        var outcome = service.confirmProposal(proposal.proposalId(), "owner-1", context);

        var failure = (ConfirmedReservationService.ConfirmationOutcome.Failure) outcome;
        assertThat(failure.code()).isEqualTo(ProposalConfirmationCode.SEATS_NO_LONGER_AVAILABLE);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("10: price change by one minor unit -> PRICE_CHANGED, fresh proposal required")
    void priceChangeByOneMinorUnit() {
        ReservationProposal proposal = activeProposal(3000L);
        stubLiveOk(1500L, 1501L);

        var outcome = service.confirmProposal(proposal.proposalId(), "owner-1", context);

        var failure = (ConfirmedReservationService.ConfirmationOutcome.Failure) outcome;
        assertThat(failure.code()).isEqualTo(ProposalConfirmationCode.PRICE_CHANGED);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("11: unbookable session -> no reservation call")
    void sessionNotBookable() {
        ReservationProposal proposal = activeProposal(3000L);
        var cancelled = new SessionBookingContextClientDto(
                sessionId, eventId, "PUBLISHED", "CANCELLED",
                clock.instant().plus(Duration.ofDays(3)), null, null, null, UUID.randomUUID());
        when(eventServiceClient.getSessionBookingContext(eq(sessionId), any())).thenReturn(cancelled);

        var outcome = service.confirmProposal(proposal.proposalId(), "owner-1", context);

        var failure = (ConfirmedReservationService.ConfirmationOutcome.Failure) outcome;
        assertThat(failure.code()).isEqualTo(ProposalConfirmationCode.SESSION_NOT_BOOKABLE);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("12: duplicate confirms reuse the identical idempotency key")
    void duplicateConfirmReusesKey() {
        ReservationProposal proposal = activeProposal(3000L);
        stubLiveOk(1500L, 1500L);
        UUID reservationId = UUID.randomUUID();
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenReturn(createdDto(reservationId, clock.instant().plus(Duration.ofMinutes(15))));
        when(reservationClient.getReservation(eq(reservationId), any()))
                .thenReturn(createdDto(reservationId, clock.instant().plus(Duration.ofMinutes(15))));

        var first = service.confirmProposal(proposal.proposalId(), "owner-1", context);
        var second = service.confirmProposal(proposal.proposalId(), "owner-1", context);

        assertThat(first).isInstanceOf(ConfirmedReservationService.ConfirmationOutcome.Success.class);
        assertThat(second).isInstanceOf(ConfirmedReservationService.ConfirmationOutcome.Success.class);
        // Exactly one write; the repeat reconciles to the same reservation.
        verify(reservationClient, times(1)).createReservation(any(), any(), any(), any(), any());
        assertThat(((ConfirmedReservationService.ConfirmationOutcome.Success) second)
                .card().reservationId()).isEqualTo(reservationId);
    }

    @Test
    @DisplayName("13: timeout-after-submit retry uses the same key and reconciles")
    void timeoutRetrySafe() {
        ReservationProposal proposal = activeProposal(3000L);
        stubLiveOk(1500L, 1500L);
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenThrow(new ReservationServiceUnavailableException(
                        AiToolError.DOWNSTREAM_TIMEOUT, "timed out"));

        var outcome = service.confirmProposal(proposal.proposalId(), "owner-1", context);

        var failure = (ConfirmedReservationService.ConfirmationOutcome.Failure) outcome;
        assertThat(failure.code())
                .isEqualTo(ProposalConfirmationCode.RESERVATION_RESULT_UNKNOWN_RETRY_SAFE);
        // Not consumed: a retry with the same key is still possible.
        assertThat(proposals.peekForOwner(proposal.proposalId(), "owner-1").status())
                .isEqualTo(ProposalStatus.ACTIVE);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(reservationClient).createReservation(any(), any(), any(), key.capture(), any());
        assertThat(key.getValue()).isEqualTo(proposal.serverIdempotencyKey());
    }

    @Test
    @DisplayName("conflict maps to RESERVATION_CONFLICT without substitution")
    void conflictMaps() {
        ReservationProposal proposal = activeProposal(3000L);
        stubLiveOk(1500L, 1500L);
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("taken",
                        com.seatflow.common.domain.enums.ErrorCode.SEAT_ALREADY_RESERVED));

        var outcome = service.confirmProposal(proposal.proposalId(), "owner-1", context);

        var failure = (ConfirmedReservationService.ConfirmationOutcome.Failure) outcome;
        assertThat(failure.code()).isEqualTo(ProposalConfirmationCode.RESERVATION_CONFLICT);
    }

    @Test
    @DisplayName("15: no payment tool exists on the confirmation path")
    void noPaymentTool() {
        var methods = ReservationServiceClient.class.getMethods();
        assertThat(List.of(methods)).extracting(m -> m.getName())
                .containsExactlyInAnyOrder("getReservation", "createReservation");
        assertThat(ConfirmedReservationService.ConfirmationOutcome.Success.class.getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("payment", "paymentToken", "stripe", "cardToken");
    }

    @Test
    @DisplayName("17: reset supersedes the active proposal but performs no hold cancellation")
    void resetSupersedesWithoutCancellingHold() {
        ReservationProposal proposal = activeProposal(3000L);
        proposals.supersedeForConversation(conversationId, "owner-1");

        assertThat(proposals.getForOwner(proposal.proposalId(), "owner-1")).isNull();
        assertThat(proposals.peekForOwner(proposal.proposalId(), "owner-1").status())
                .isEqualTo(ProposalStatus.SUPERSEDED);
        // The store exposes no cancellation primitive: holds live only in Reservation Service.
        assertThat(ProposalStore.class.getMethods()).extracting(m -> m.getName())
                .doesNotContain("cancelReservation", "cancelHold", "deleteHold");
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("19: secrets/JWT are absent from proposal storage and confirmation card")
    void noSecretsStoredOrReturned() {
        ReservationProposal proposal = activeProposal(3000L);
        stubLiveOk(1500L, 1500L);
        UUID reservationId = UUID.randomUUID();
        when(reservationClient.createReservation(any(), any(), any(), any(), any()))
                .thenReturn(createdDto(reservationId, clock.instant().plus(Duration.ofMinutes(15))));

        var outcome = service.confirmProposal(proposal.proposalId(), "owner-1", context);
        var card = ((ConfirmedReservationService.ConfirmationOutcome.Success) outcome).card();

        assertThat(proposal.toString()).doesNotContain("bearer-owner-jwt", "eyJ", "GROQ");
        assertThat(card.toString()).doesNotContain("bearer-owner-jwt", "eyJ", "GROQ");
    }

    @Test
    @DisplayName("7: chat path has no reservation-write dependency (typed yes cannot write)")
    void chatPathCannotWrite() {
        var writerFields = List.of(AssistantOrchestrator.class.getDeclaredFields()).stream()
                .filter(field -> field.getType().getSimpleName().contains("ReservationServiceClient")
                        || field.getType().getSimpleName().contains("ConfirmedReservation"))
                .toList();
        assertThat(writerFields).isEmpty();
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
