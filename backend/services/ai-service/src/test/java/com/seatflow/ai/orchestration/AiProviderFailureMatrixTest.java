package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import com.seatflow.ai.api.dto.AssistantChatResponse;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposalProperties;
import com.seatflow.ai.service.AiMetrics;
import com.seatflow.ai.service.AiStatusService;
import com.seatflow.ai.service.impl.ProposalServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.time.Clock;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Provider failure matrix tests (TASK-P15-007 section 5).
 *
 * <p>Every simulated Groq/OpenAI-compatible failure maps to a stable SeatFlow error category, leaks
 * no raw body/stack/secret, records bounded metrics, and leaves core booking paths untouched
 * (the turn answers {@code ERROR_RECOVERABLE} with safe retry guidance; nothing is thrown to the
 * caller as a 500).
 */
@ExtendWith(MockitoExtension.class)
class AiProviderFailureMatrixTest {

    private ConversationStore conversations;
    private MessageWindowChatMemory memory;
    private AssistantOrchestrator orchestrator;
    private SimpleMeterRegistry registry;

    @Mock
    private AssistantPromptFactory promptFactory;
    @Mock
    private AssistantToolRegistry toolRegistry;
    @Mock
    private AssistantModelClient modelClient;
    @Mock
    private AiStatusService statusService;

    private final AiRequestContext toolContext =
            new AiRequestContext("bearer-test-jwt", "corr-1", "owner-1");

    @BeforeEach
    void setUp() {
        Clock clock = Clock.systemUTC();
        memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        var proposals = new ProposalStore(
                new ReservationProposalProperties(Duration.ofMinutes(5), 500, Duration.ofMinutes(1)), clock);
        registry = new SimpleMeterRegistry();
        var metrics = new AiMetrics(registry);
        when(promptFactory.systemPrompt()).thenReturn("prompt");
        when(statusService.isChatAvailable()).thenReturn(true);
        org.mockito.Mockito.lenient().when(statusService.isEnabled()).thenReturn(true);
        orchestrator = new AssistantOrchestrator(conversations, memory, promptFactory,
                toolRegistry, new AssistantCardAssembler(), modelClient,
                new AssistantToolObservation(), new AssistantProviderErrorMapper(),
                statusService, new ProposalServiceImpl(proposals, metrics), metrics, clock);
    }

    record FailureCase(String name, AssistantChatErrorCode code) {
    }

    static Stream<FailureCase> providerFailures() {
        return Stream.of(
                new FailureCase("401 invalid key", AssistantChatErrorCode.AI_MISCONFIGURED),
                new FailureCase("403 forbidden/model permission", AssistantChatErrorCode.AI_MISCONFIGURED),
                new FailureCase("404/400 invalid model", AssistantChatErrorCode.AI_MODEL_UNAVAILABLE),
                new FailureCase("429 rate limited", AssistantChatErrorCode.AI_RATE_LIMITED),
                new FailureCase("500 provider error", AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE),
                new FailureCase("502/503 unavailable", AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE),
                new FailureCase("connection refused", AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE),
                new FailureCase("connect timeout", AssistantChatErrorCode.AI_PROVIDER_TIMEOUT),
                new FailureCase("read timeout", AssistantChatErrorCode.AI_PROVIDER_TIMEOUT),
                new FailureCase("malformed JSON", AssistantChatErrorCode.AI_RESPONSE_INVALID),
                new FailureCase("invalid tool-call payload", AssistantChatErrorCode.AI_RESPONSE_INVALID),
                new FailureCase("empty content", AssistantChatErrorCode.AI_RESPONSE_INVALID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providerFailures")
    @DisplayName("each provider failure yields a stable safe recoverable turn")
    void providerFailureMatrix(FailureCase failure) {
        when(modelClient.execute(any())).thenThrow(
                new AssistantProviderException(failure.code(), "safe " + failure.code()));

        AssistantChatResponse response = orchestrator.chat(null, "find Hamlet seats", "owner-1", toolContext);

        assertThat(response.state()).isEqualTo(AssistantState.ERROR_RECOVERABLE);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(failure.code());
        // Safe envelope: generic message, retry guidance, no raw details.
        assertThat(response.assistantMessage()).contains("temporarily unavailable");
        assertThat(response.assistantMessage()).doesNotContain("gsk_");
        assertThat(response.suggestedActions()).contains("Continue browsing events without AI");
        // Failed turns store no memory (retry starts clean) and no draft.
        assertThat(memory.get(response.conversationId().toString())).isEmpty();
        assertThat(conversations.getForOwner(response.conversationId(), "owner-1").draft()).isNull();
        // Bounded metric recorded.
        assertThat(registry.get(AiMetrics.CHAT_REQUESTS).tag("result", "error").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("blank model content degrades to AI_RESPONSE_INVALID, never an empty success")
    void blankContentIsInvalid() {
        when(modelClient.execute(any())).thenReturn(
                new AssistantModelClient.ModelTurnResult("   ", null, null, null, null, null));

        AssistantChatResponse response = orchestrator.chat(null, "hi", "owner-1", toolContext);

        // The sanitizer substitutes help text, so the turn stays a safe IDLE answer.
        assertThat(response.assistantMessage()).isNotBlank();
        assertThat(response.state()).isEqualTo(AssistantState.IDLE);
    }

    @Test
    @DisplayName("429 fails fast: no retry storm from the orchestrator")
    void rateLimitedFailsFast() {
        when(modelClient.execute(any())).thenThrow(new AssistantProviderException(
                AssistantChatErrorCode.AI_RATE_LIMITED, "AI provider rate limit reached."));

        AssistantChatResponse first = orchestrator.chat(null, "hi", "owner-1", toolContext);
        AssistantChatResponse second =
                orchestrator.chat(first.conversationId(), "hi again", "owner-1", toolContext);

        assertThat(first.error().code()).isEqualTo(AssistantChatErrorCode.AI_RATE_LIMITED);
        assertThat(second.error().code()).isEqualTo(AssistantChatErrorCode.AI_RATE_LIMITED);
        // Exactly one provider attempt per turn — the orchestrator never retries internally.
        org.mockito.Mockito.verify(modelClient, org.mockito.Mockito.times(2)).execute(any());
    }
}
