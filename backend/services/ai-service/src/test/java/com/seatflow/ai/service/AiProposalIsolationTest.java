package com.seatflow.ai.service;

import com.seatflow.ai.client.EventServiceClient;
import com.seatflow.ai.client.ReservationServiceClient;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.orchestration.AssistantCardAssembler;
import com.seatflow.ai.orchestration.AssistantConversationProperties;
import com.seatflow.ai.orchestration.AssistantModelClient;
import com.seatflow.ai.orchestration.AssistantOrchestrator;
import com.seatflow.ai.orchestration.AssistantPromptFactory;
import com.seatflow.ai.orchestration.AssistantProviderErrorMapper;
import com.seatflow.ai.orchestration.AssistantToolObservation;
import com.seatflow.ai.orchestration.AssistantToolRegistry;
import com.seatflow.ai.orchestration.ConversationStore;
import com.seatflow.ai.orchestration.MutableClock;
import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposal;
import com.seatflow.ai.proposal.ReservationProposalProperties;
import com.seatflow.ai.service.impl.ConfirmedReservationServiceImpl;
import com.seatflow.ai.service.impl.ProposalServiceImpl;
import com.seatflow.ai.service.seat.SeatCandidateAssembler;
import com.seatflow.ai.tool.dto.AvailableSeatItem;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Conversation/proposal isolation tests (TASK-P15-007 section 4.3).
 */
@ExtendWith(MockitoExtension.class)
class AiProposalIsolationTest {

    private MutableClock clock;
    private ProposalStore proposals;
    private ConversationStore conversations;
    private ConfirmedReservationServiceImpl confirmations;
    private AiMetrics metrics;

    @Mock
    private EventServiceClient eventServiceClient;
    @Mock
    private SeatCandidateAssembler assembler;
    @Mock
    private ReservationServiceClient reservationClient;
    @Mock
    private AssistantPromptFactory promptFactory;
    @Mock
    private AssistantToolRegistry toolRegistry;
    @Mock
    private AssistantModelClient modelClient;
    @Mock
    private AiStatusService statusService;

    private UUID conversationId;
    private final AiRequestContext ownerContext =
            new AiRequestContext("bearer-owner", "corr-1", "owner-1");

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-07T10:00:00Z"));
        proposals = new ProposalStore(
                new ReservationProposalProperties(Duration.ofMinutes(5), 500, Duration.ofMinutes(1)), clock);
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        metrics = new AiMetrics(new SimpleMeterRegistry());
        confirmations = new ConfirmedReservationServiceImpl(
                proposals, conversations, eventServiceClient, assembler, reservationClient, metrics, clock);
        conversationId = conversations.create("owner-1").conversationId();
    }

    private ReservationProposal activeProposal() {
        UUID seatA = UUID.randomUUID();
        UUID seatB = UUID.randomUUID();
        return proposals.create(conversationId, "owner-1", UUID.randomUUID(), UUID.randomUUID(),
                List.of(seatA, seatB),
                List.of(new ReservationProposal.SeatDisplay(seatA, "Row A Seat 1", "S", "A", 1),
                        new ReservationProposal.SeatDisplay(seatB, "Row A Seat 2", "S", "A", 2)),
                List.of(UUID.randomUUID(), UUID.randomUUID()), null, null, null, 2000L, "EUR");
    }

    @Test
    @DisplayName("user A cannot confirm user B's proposal (404 anti-enumeration, no write)")
    void crossOwnerConfirmIsNotFound() {
        ReservationProposal bobs = activeProposal();

        var outcome = confirmations.confirmProposal(bobs.proposalId(), "owner-2",
                new AiRequestContext("bearer-other", "corr-2", "owner-2"));

        assertThat(outcome).isInstanceOf(
                ConfirmedReservationService.ConfirmationOutcome.Failure.class);
        var failure = (ConfirmedReservationService.ConfirmationOutcome.Failure) outcome;
        assertThat(failure.code()).isEqualTo(ProposalConfirmationCode.PROPOSAL_NOT_FOUND);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("expired proposals cannot be confirmed (410, no write)")
    void expiredProposalCannotConfirm() {
        ReservationProposal proposal = activeProposal();
        clock.advance(Duration.ofMinutes(6));

        var outcome = confirmations.confirmProposal(proposal.proposalId(), "owner-1", ownerContext);

        assertThat(outcome).isInstanceOf(
                ConfirmedReservationService.ConfirmationOutcome.Failure.class);
        assertThat(((ConfirmedReservationService.ConfirmationOutcome.Failure) outcome).code())
                .isEqualTo(ProposalConfirmationCode.PROPOSAL_EXPIRED);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("superseded proposals cannot be confirmed (409, no write)")
    void supersededProposalCannotConfirm() {
        ReservationProposal first = activeProposal();
        activeProposal();

        var outcome = confirmations.confirmProposal(first.proposalId(), "owner-1", ownerContext);

        assertThat(outcome).isInstanceOf(
                ConfirmedReservationService.ConfirmationOutcome.Failure.class);
        assertThat(((ConfirmedReservationService.ConfirmationOutcome.Failure) outcome).code())
                .isEqualTo(ProposalConfirmationCode.PROPOSAL_SUPERSEDED);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("restart-lost state cannot be reconstructed from client-controlled fields")
    void restartLosesState() {
        ReservationProposal proposal = activeProposal();
        UUID lostConversation = conversationId;
        UUID lostProposal = proposal.proposalId();

        // Simulate a process restart: brand-new empty stores on the same clock.
        var freshMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        var freshConversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, freshMemory);
        var freshProposals = new ProposalStore(
                new ReservationProposalProperties(Duration.ofMinutes(5), 500, Duration.ofMinutes(1)), clock);
        var freshConfirmations = new ConfirmedReservationServiceImpl(
                freshProposals, freshConversations, eventServiceClient, assembler,
                reservationClient, metrics, clock);

        assertThat(freshConversations.getForOwner(lostConversation, "owner-1")).isNull();
        var outcome = freshConfirmations.confirmProposal(lostProposal, "owner-1", ownerContext);
        assertThat(outcome).isInstanceOf(
                ConfirmedReservationService.ConfirmationOutcome.Failure.class);
        assertThat(((ConfirmedReservationService.ConfirmationOutcome.Failure) outcome).code())
                .isEqualTo(ProposalConfirmationCode.PROPOSAL_NOT_FOUND);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("model text cannot revive an expired/superseded proposal; only fresh turns mint IDs")
    void modelTextCannotReviveProposals() {
        when(promptFactory.systemPrompt()).thenReturn("prompt");
        when(statusService.isChatAvailable()).thenReturn(true);
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        var convos = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        var proposalService = new ProposalServiceImpl(proposals, metrics);
        // Confirmation must share the orchestrator's conversation store: ownership is checked
        // against the same registry that owns the conversation.
        var confirm = new ConfirmedReservationServiceImpl(
                proposals, convos, eventServiceClient, assembler, reservationClient, metrics, clock);
        var orchestrator = new AssistantOrchestrator(convos, memory, promptFactory,
                toolRegistry, new AssistantCardAssembler(), modelClient,
                new AssistantToolObservation(), new AssistantProviderErrorMapper(),
                statusService, proposalService, metrics, clock);

        UUID sessionId = UUID.randomUUID();
        when(modelClient.execute(any())).thenAnswer(invocation -> new AssistantModelClient.ModelTurnResult(
                "prose", null, null, null, null, bestSeats(sessionId, UUID.randomUUID())));
        var first = orchestrator.chat(null, "2 seats", "owner-1", ownerContext);
        UUID firstProposal = proposalCardId(first);
        // New constraints supersede the first secure proposal with a fresh ID.
        when(modelClient.execute(any())).thenAnswer(invocation -> new AssistantModelClient.ModelTurnResult(
                "prose", null, null, null, null, bestSeats(sessionId, UUID.randomUUID())));
        var second = orchestrator.chat(first.conversationId(), "2 other seats", "owner-1", ownerContext);
        UUID secondProposal = proposalCardId(second);
        assertThat(secondProposal).isNotEqualTo(firstProposal);

        // Model prose alone ("confirm the old one") mints nothing and revives nothing.
        when(modelClient.execute(any())).thenReturn(new AssistantModelClient.ModelTurnResult(
                "confirmed (not authoritative)", null, null, null, null, null));
        orchestrator.chat(first.conversationId(), "yes confirm the first proposal", "owner-1", ownerContext);

        var stale = confirm.confirmProposal(firstProposal, "owner-1", ownerContext);
        assertThat(((ConfirmedReservationService.ConfirmationOutcome.Failure) stale).code())
                .isEqualTo(ProposalConfirmationCode.PROPOSAL_SUPERSEDED);
        verify(reservationClient, never()).createReservation(any(), any(), any(), any(), any());
    }

    private static FindBestSeatsResult bestSeats(UUID sessionId, UUID seatId) {
        var candidate = new FindBestSeatsResult.SeatCandidate(List.of(seatId),
                List.of(new AvailableSeatItem(seatId, UUID.randomUUID(), "S", "A", 1,
                        BigDecimal.ZERO, BigDecimal.ZERO, "STD", UUID.randomUUID(),
                        1000L, "EUR", "AVAILABLE")),
                1000L, "EUR", true, List.of("together"), 1);
        return new FindBestSeatsResult(sessionId, Instant.now(), "OK",
                List.of(candidate), List.of(), List.of());
    }

    private static UUID proposalCardId(com.seatflow.ai.api.dto.AssistantChatResponse response) {
        return response.cards().stream()
                .filter(card -> card.type().name().equals("RESERVATION_PROPOSAL"))
                .findFirst()
                .orElseThrow()
                .proposalId();
    }
}
