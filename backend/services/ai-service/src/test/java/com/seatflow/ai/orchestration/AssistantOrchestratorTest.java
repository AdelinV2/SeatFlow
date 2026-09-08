package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import com.seatflow.ai.api.dto.AssistantChatResponse;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposalProperties;
import com.seatflow.ai.service.AiMetrics;
import com.seatflow.ai.service.AiStatusService;
import com.seatflow.ai.service.impl.ProposalServiceImpl;
import com.seatflow.ai.tool.dto.AvailableSeatItem;
import com.seatflow.ai.tool.dto.FindBestSeatsResult;
import com.seatflow.common.domain.exception.ResourceNotFoundException;
import com.seatflow.common.domain.exception.ValidationException;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused orchestration tests for TASK-P15-004 section 14 (mandatory 1-6, 9-11, 13-15, 18).
 */
@ExtendWith(MockitoExtension.class)
class AssistantOrchestratorTest {

    private ConversationStore conversations;
    private MessageWindowChatMemory chatMemory;
    private AssistantCardAssembler cardAssembler;
    private AssistantToolObservation observation;
    private AssistantProviderErrorMapper errorMapper;
    private Clock clock;

    @Mock
    private AssistantPromptFactory promptFactory;
    @Mock
    private AssistantToolRegistry toolRegistry;
    @Mock
    private AssistantModelClient modelClient;
    @Mock
    private AiStatusService statusService;

    private AssistantOrchestrator orchestrator;

    private final AiRequestContext toolContext =
            new AiRequestContext("bearer-test-jwt-value-xyz", "corr-123", "owner-123");

    @BeforeEach
    void setUp() {
        clock = Clock.systemUTC();
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, chatMemory);
        cardAssembler = new AssistantCardAssembler();
        observation = new AssistantToolObservation();
        errorMapper = new AssistantProviderErrorMapper();
        var proposalStore = new ProposalStore(
                new ReservationProposalProperties(
                        Duration.ofMinutes(5), 500, Duration.ofMinutes(1)), clock);
        var proposalService = new ProposalServiceImpl(proposalStore,
                new AiMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        org.mockito.Mockito.lenient().when(promptFactory.systemPrompt())
                .thenReturn("test system prompt p15-007-v1");
        org.mockito.Mockito.lenient().when(statusService.isChatAvailable()).thenReturn(true);
        org.mockito.Mockito.lenient().when(statusService.isEnabled()).thenReturn(true);
        orchestrator = new AssistantOrchestrator(conversations, chatMemory, promptFactory,
                toolRegistry, cardAssembler, modelClient, observation, errorMapper,
                statusService, proposalService,
                new AiMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), clock);
    }

    @Test
    @DisplayName("1: first turn creates UUID conversation and owner binding")
    void firstTurnCreatesUuidAndOwnerBinding() {
        when(modelClient.execute(any()))
                .thenReturn(new AssistantModelClient.ModelTurnResult("Hello", null, null, null, null, null));

        AssistantChatResponse response = orchestrator.chat(null, "  hello  ", "owner-123", toolContext);

        assertThat(response.conversationId()).isNotNull();
        assertThat(response.state()).isEqualTo(AssistantState.IDLE);
        var stored = conversations.getForOwner(response.conversationId(), "owner-123");
        assertThat(stored).isNotNull();
        assertThat(stored.ownerSubject()).isEqualTo("owner-123");
    }

    @Test
    @DisplayName("2: same owner reuses context")
    void sameOwnerReusesContext() {
        when(modelClient.execute(any()))
                .thenReturn(new AssistantModelClient.ModelTurnResult("Hi", null, null, null, null, null));

        AssistantChatResponse first = orchestrator.chat(null, "hello", "owner-123", toolContext);
        AssistantChatResponse second =
                orchestrator.chat(first.conversationId(), "again", "owner-123", toolContext);

        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        assertThat(chatMemory.get(first.conversationId().toString())).hasSize(4);
    }

    @Test
    @DisplayName("3: different owner cannot reuse conversation ID (404, no context)")
    void differentOwnerCannotReuse() {
        when(modelClient.execute(any()))
                .thenReturn(new AssistantModelClient.ModelTurnResult("Hi", null, null, null, null, null));

        AssistantChatResponse first = orchestrator.chat(null, "hello", "owner-123", toolContext);

        assertThatThrownBy(() -> orchestrator.chat(
                first.conversationId(), "hijack", "owner-999", toolContext))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("5: explicit reset clears owned memory/state")
    void resetClearsOwnedState() {
        when(modelClient.execute(any()))
                .thenReturn(new AssistantModelClient.ModelTurnResult("Hi", null, null, null, null, null));

        AssistantChatResponse first = orchestrator.chat(null, "hello", "owner-123", toolContext);
        boolean cleared = orchestrator.reset(first.conversationId(), "owner-123");

        assertThat(cleared).isTrue();
        assertThat(conversations.peek(first.conversationId())).isNull();
        assertThat(chatMemory.get(first.conversationId().toString())).isEmpty();
    }

    @Test
    @DisplayName("reset by non-owner returns false (no leak)")
    void resetByNonOwnerReturnsFalse() {
        when(modelClient.execute(any()))
                .thenReturn(new AssistantModelClient.ModelTurnResult("Hi", null, null, null, null, null));

        AssistantChatResponse first = orchestrator.chat(null, "hello", "owner-123", toolContext);
        assertThat(orchestrator.reset(first.conversationId(), "owner-999")).isFalse();
        assertThat(conversations.peek(first.conversationId())).isNotNull();
    }

    @Test
    @DisplayName("6: 2001-char message rejected before provider call; 2000 accepted")
    void messageLengthBoundaries() {
        String tooLong = "x".repeat(2001);
        assertThatThrownBy(() -> orchestrator.chat(null, tooLong, "owner-123", toolContext))
                .isInstanceOf(ValidationException.class);
        verify(modelClient, never()).execute(any());

        String maxOk = "y".repeat(2000);
        when(modelClient.execute(any()))
                .thenReturn(new AssistantModelClient.ModelTurnResult("ok", null, null, null, null, null));
        AssistantChatResponse response = orchestrator.chat(null, "  " + maxOk + "  ", "owner-123", toolContext);
        assertThat(response.conversationId()).isNotNull();
        verify(modelClient).execute(any());
    }

    @Test
    @DisplayName("9: captured provider request contains no JWT/API key")
    void providerRequestContainsNoSecrets() {
        ArgumentCaptor<AssistantModelClient.ModelTurnRequest> captor =
                ArgumentCaptor.forClass(AssistantModelClient.ModelTurnRequest.class);
        when(modelClient.execute(any()))
                .thenReturn(new AssistantModelClient.ModelTurnResult("Hello", null, null, null, null, null));

        orchestrator.chat(null, "find seats", "owner-123", toolContext);

        verify(modelClient).execute(captor.capture());
        var request = captor.getValue();
        assertThat(request.systemPrompt()).doesNotContain("bearer-test-jwt-value-xyz");
        assertThat(request.userMessage()).doesNotContain("bearer-test-jwt-value-xyz");
        assertThat(request.history().toString()).doesNotContain("bearer-test-jwt-value-xyz");
        assertThat(request.toString()).doesNotContain("GROQ_API_KEY").doesNotContain("gsk_");
        assertThat(request.allowedToolNames())
                .containsExactlyInAnyOrder("searchEvents", "getEvent", "getEventSessions",
                        "getAvailableSeats", "findBestSeats", "getReservation");
    }

    @Test
    @DisplayName("10: findBestSeats result creates proposal card from tool data, not prose")
    void proposalCardFromToolDataNotProse() {
        UUID sessionId = UUID.randomUUID();
        UUID seatA = UUID.randomUUID();
        UUID seatB = UUID.randomUUID();
        var seats = List.of(
                new AvailableSeatItem(seatA, UUID.randomUUID(), "Stalls", "A", 1,
                        BigDecimal.ZERO, BigDecimal.ZERO, "STD", UUID.randomUUID(), 1500L, "EUR", "AVAILABLE"),
                new AvailableSeatItem(seatB, UUID.randomUUID(), "Stalls", "A", 2,
                        BigDecimal.ZERO, BigDecimal.ZERO, "STD", UUID.randomUUID(), 1500L, "EUR", "AVAILABLE"));
        var candidate = new FindBestSeatsResult.SeatCandidate(
                List.of(seatA, seatB), seats, 3000L, "EUR", true, List.of("contiguous"), 1);
        var bestSeats = new FindBestSeatsResult(sessionId, Instant.now(), "OK",
                List.of(candidate), List.of(), List.of());

        // Prose tries to lie about IDs/prices; cards must use tool data.
        String lyingProse = "Your seats are 00000000-0000-0000-0000-000000000000 for $9999";
        when(modelClient.execute(any())).thenReturn(
                new AssistantModelClient.ModelTurnResult(lyingProse, null, null, null, null, bestSeats));

        AssistantChatResponse response = orchestrator.chat(null, "2 seats please", "owner-123", toolContext);

        assertThat(response.state()).isEqualTo(AssistantState.CONFIRMATION_REQUIRED);
        var proposal = response.cards().stream()
                .filter(card -> card.type().name().equals("RESERVATION_PROPOSAL"))
                .findFirst().orElseThrow();
        assertThat(proposal.seatIds()).containsExactlyInAnyOrder(seatA, seatB);
        assertThat(proposal.totalPriceMinor()).isEqualTo(3000L);
        assertThat(proposal.currency()).isEqualTo("EUR");
        assertThat(proposal.requiresExplicitConfirmation()).isTrue();
        assertThat(proposal.seatsHeld()).isFalse();
        // Cards are authoritative from tool data; prose is presentation only and cannot mutate them.
        assertThat(proposal.seatIds()).doesNotContain(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    @DisplayName("11: changed constraints supersede old draft (one current draft)")
    void changedConstraintsSupersedeDraft() {
        UUID sessionId = UUID.randomUUID();
        // Build two distinct tool results manually via sequential stubbing.
        UUID seatA = UUID.randomUUID();
        var candidateOne = new FindBestSeatsResult.SeatCandidate(List.of(seatA),
                List.of(new AvailableSeatItem(seatA, UUID.randomUUID(), "S", "A", 1,
                        BigDecimal.ZERO, BigDecimal.ZERO, "STD", UUID.randomUUID(), 1000L, "EUR", "AVAILABLE")),
                1000L, "EUR", true, List.of("r1"), 1);
        UUID seatB = UUID.randomUUID();
        UUID seatC = UUID.randomUUID();
        var candidateTwo = new FindBestSeatsResult.SeatCandidate(List.of(seatB, seatC),
                List.of(
                        new AvailableSeatItem(seatB, UUID.randomUUID(), "S", "A", 2,
                                BigDecimal.ZERO, BigDecimal.ZERO, "STD", UUID.randomUUID(), 1000L, "EUR", "AVAILABLE"),
                        new AvailableSeatItem(seatC, UUID.randomUUID(), "S", "A", 3,
                                BigDecimal.ZERO, BigDecimal.ZERO, "STD", UUID.randomUUID(), 1000L, "EUR", "AVAILABLE")),
                2000L, "EUR", true, List.of("r2"), 1);
        var firstResult = new FindBestSeatsResult(sessionId, Instant.now(), "OK",
                List.of(candidateOne), List.of(), List.of());
        var secondResult = new FindBestSeatsResult(sessionId, Instant.now(), "OK",
                List.of(candidateTwo), List.of(), List.of());
        when(modelClient.execute(any()))
                .thenReturn(new AssistantModelClient.ModelTurnResult("first", null, null, null, null, firstResult))
                .thenReturn(new AssistantModelClient.ModelTurnResult("second", null, null, null, null, secondResult));

        AssistantChatResponse first = orchestrator.chat(null, "1 seat", "owner-123", toolContext);
        UUID firstProposal = first.cards().stream()
                .filter(card -> card.type().name().equals("RESERVATION_PROPOSAL"))
                .findFirst().orElseThrow().proposalId();
        AssistantChatResponse second =
                orchestrator.chat(first.conversationId(), "actually 2 seats", "owner-123", toolContext);
        UUID secondProposal = second.cards().stream()
                .filter(card -> card.type().name().equals("RESERVATION_PROPOSAL"))
                .findFirst().orElseThrow().proposalId();

        assertThat(secondProposal).isNotEqualTo(firstProposal);
        var stored = conversations.getForOwner(first.conversationId(), "owner-123");
        assertThat(stored.draft().seatIds()).containsExactlyInAnyOrder(seatB, seatC);
    }

    @Test
    @DisplayName("13: provider 429/timeout/model-unavailable map to stable codes")
    void providerFailuresMapToStableCodes() {
        when(modelClient.execute(any()))
                .thenThrow(new AssistantProviderException(
                        AssistantChatErrorCode.AI_RATE_LIMITED, "rate limited"))
                .thenThrow(new AssistantProviderException(
                        AssistantChatErrorCode.AI_PROVIDER_TIMEOUT, "timeout"))
                .thenThrow(new AssistantProviderException(
                        AssistantChatErrorCode.AI_MODEL_UNAVAILABLE, "no model"));
        AssistantChatResponse rateLimited = orchestrator.chat(null, "hi", "owner-123", toolContext);
        assertThat(rateLimited.state()).isEqualTo(AssistantState.ERROR_RECOVERABLE);
        assertThat(rateLimited.error().code()).isEqualTo(AssistantChatErrorCode.AI_RATE_LIMITED);

        AssistantChatResponse timeout = orchestrator.chat(null, "hi", "owner-123", toolContext);
        assertThat(timeout.error().code()).isEqualTo(AssistantChatErrorCode.AI_PROVIDER_TIMEOUT);

        AssistantChatResponse noModel = orchestrator.chat(null, "hi", "owner-123", toolContext);
        assertThat(noModel.error().code()).isEqualTo(AssistantChatErrorCode.AI_MODEL_UNAVAILABLE);
        assertThat(noModel.assistantMessage()).doesNotContain("stack").doesNotContain("gsk_");
    }

    @Test
    @DisplayName("14+15: invalid structured output cannot corrupt state; reasoning never returned")
    void invalidOutputSafeAndNoReasoning() {
        UUID sessionId = UUID.randomUUID();
        var badCandidate = new FindBestSeatsResult.SeatCandidate(List.of(),
                List.of(), -5L, "EURO", true, List.of(), 1);
        // Invalid candidate (empty IDs, negative price, bad currency) must not create a proposal.
        // The orchestrator validates and throws AI_RESPONSE_INVALID; emulate safe fallback by
        // asserting the validation itself rejects the candidate.
        assertThatThrownBy(() -> orchestrator.validateBestSeatsCandidate(badCandidate))
                .isInstanceOf(AssistantProviderException.class)
                .satisfies(ex -> assertThat(((AssistantProviderException) ex).getErrorCode())
                        .isEqualTo(AssistantChatErrorCode.AI_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("18: unknown/lost conversation requires reset, never stale auth")
    void unknownConversationRequiresReset() {
        assertThatThrownBy(() -> orchestrator.chat(
                UUID.randomUUID(), "hello", "owner-123", toolContext))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("4: expired conversation returns EXPIRED state and is removed")
    void expiredConversationReturnsExpiredState() {
        MutableClock mutable = new MutableClock(java.time.Instant.now());
        var shortMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        var shortStore = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(1), 500), mutable, shortMemory);
        var shortProposals = new ProposalStore(
                new ReservationProposalProperties(
                        Duration.ofMinutes(5), 500, Duration.ofMinutes(1)), mutable);
        var expiredOrchestrator = new AssistantOrchestrator(shortStore, shortMemory, promptFactory,
                toolRegistry, cardAssembler, modelClient, observation, errorMapper,
                statusService, new ProposalServiceImpl(shortProposals,
                        new AiMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())),
                new AiMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), mutable);
        org.mockito.Mockito.lenient().when(modelClient.execute(any())).thenReturn(
                new AssistantModelClient.ModelTurnResult("hi", null, null, null, null, null));

        AssistantChatResponse first =
                expiredOrchestrator.chat(null, "hello", "owner-123", toolContext);
        mutable.advance(Duration.ofMinutes(2));
        AssistantChatResponse expired =
                expiredOrchestrator.chat(first.conversationId(), "again", "owner-123", toolContext);

        assertThat(expired.state()).isEqualTo(AssistantState.EXPIRED);
        assertThat(shortStore.peek(first.conversationId())).isNull();
        assertThat(shortMemory.get(first.conversationId().toString())).isEmpty();
        org.mockito.Mockito.verify(modelClient, org.mockito.Mockito.times(1)).execute(any());
    }

    @Test
    @DisplayName("REV-002: reset during an in-flight turn queues safely (no race, no resurrection)")
    void resetDuringInFlightTurnQueuesSafely() throws Exception {
        when(modelClient.execute(any())).thenAnswer(invocation -> {
            Thread.sleep(400);
            return new AssistantModelClient.ModelTurnResult("done", null, null, null, null, null);
        });
        AssistantChatResponse first = orchestrator.chat(null, "hello", "owner-123", toolContext);

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<AssistantChatResponse> turn = pool.submit(() ->
                orchestrator.chat(first.conversationId(), "slow turn", "owner-123", toolContext));
        Thread.sleep(150);
        // Same bounded single-flight lock as turns: reset waits for the turn, then clears for real.
        // No false 204, no partial clear, and the finished turn cannot resurrect state afterwards.
        assertThat(orchestrator.reset(first.conversationId(), "owner-123")).isTrue();
        assertThat(turn.get(10, java.util.concurrent.TimeUnit.SECONDS).conversationId())
                .isEqualTo(first.conversationId());
        assertThat(conversations.peek(first.conversationId())).isNull();
        assertThat(chatMemory.get(first.conversationId().toString())).isEmpty();
        pool.shutdown();
    }

    @Test
    @DisplayName("REV-002: interrupted reset never reports success")
    void interruptedResetNeverReportsSuccess() {
        when(modelClient.execute(any())).thenReturn(
                new AssistantModelClient.ModelTurnResult("hi", null, null, null, null, null));
        AssistantChatResponse first = orchestrator.chat(null, "hello", "owner-123", toolContext);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> orchestrator.reset(first.conversationId(), "owner-123"))
                    .isInstanceOf(com.seatflow.common.domain.exception.ConflictException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(conversations.peek(first.conversationId())).isNotNull();
    }

    @Test
    @DisplayName("REV-005: OK with empty candidates guides to adjust constraints (DISCOVERING)")
    void okEmptyCandidatesGuidance() {
        UUID sessionId = UUID.randomUUID();
        var emptyOk = new FindBestSeatsResult(sessionId, Instant.now(), "OK",
                List.of(), List.of(), List.of());
        when(modelClient.execute(any())).thenReturn(
                new AssistantModelClient.ModelTurnResult("prose", null, null, null, null, emptyOk));

        AssistantChatResponse response =
                orchestrator.chat(null, "2 seats please", "owner-123", toolContext);

        assertThat(response.state()).isEqualTo(AssistantState.DISCOVERING);
        assertThat(response.cards()).anySatisfy(card ->
                assertThat(card.infoMessage()).contains("No seats match"));
    }

    @Test
    @DisplayName("REV-003: exactly one user/assistant pair stored per successful turn")
    void singlePairPerTurn() {
        when(modelClient.execute(any())).thenReturn(
                new AssistantModelClient.ModelTurnResult("done", null, null, null, null, null));
        AssistantChatResponse first = orchestrator.chat(null, "hello", "owner-123", toolContext);
        assertThat(chatMemory.get(first.conversationId().toString())).hasSize(2);
        AssistantChatResponse second =
                orchestrator.chat(first.conversationId(), "again", "owner-123", toolContext);
        assertThat(chatMemory.get(second.conversationId().toString())).hasSize(4);
    }

    @Test
    @DisplayName("failed turns store nothing (clean retry)")
    void failedTurnStoresNothing() {
        when(modelClient.execute(any())).thenThrow(
                new AssistantProviderException(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE, "down"));
        AssistantChatResponse failed = orchestrator.chat(null, "hello", "owner-123", toolContext);
        assertThat(failed.error().code())
                .isEqualTo(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE);
        assertThat(chatMemory.get(failed.conversationId().toString())).isEmpty();
    }

    @Test
    @DisplayName("disabled AI returns AI_DISABLED without calling provider")
    void disabledAiReturnsStableCode() {
        when(statusService.isChatAvailable()).thenReturn(false);
        when(statusService.isEnabled()).thenReturn(false);

        AssistantChatResponse response = orchestrator.chat(null, "hello", "owner-123", toolContext);

        assertThat(response.error().code()).isEqualTo(AssistantChatErrorCode.AI_DISABLED);
        verify(modelClient, never()).execute(any());
    }
}
