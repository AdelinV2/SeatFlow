package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import com.seatflow.ai.api.dto.AssistantChatResponse;
import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.proposal.ProposalStore;
import com.seatflow.ai.proposal.ReservationProposalProperties;
import com.seatflow.ai.service.AiMetrics;
import com.seatflow.ai.service.AiStatusService;
import com.seatflow.ai.service.impl.ProposalServiceImpl;
import com.seatflow.ai.tool.dto.EventSearchItem;
import com.seatflow.ai.tool.dto.SearchEventsResult;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Deterministic fallback when model prose fails after tools already succeeded (e.g. a rate
 * limit on the tool follow-up turn): the turn answers from observed tool DTOs with short
 * customer-friendly prose instead of discarding everything as an error.
 */
@ExtendWith(MockitoExtension.class)
class AssistantFallbackResponseTest {

    private ConversationStore conversations;
    private MessageWindowChatMemory memory;
    private AssistantToolObservation observation;
    private AssistantOrchestrator orchestrator;
    private Clock clock;

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
        clock = Clock.systemUTC();
        memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        observation = new AssistantToolObservation();
        var proposals = new ProposalStore(
                new ReservationProposalProperties(Duration.ofMinutes(5), 500, Duration.ofMinutes(1)), clock);
        var metrics = new AiMetrics(new SimpleMeterRegistry());
        when(promptFactory.systemPrompt()).thenReturn("prompt");
        when(statusService.isChatAvailable()).thenReturn(true);
        org.mockito.Mockito.lenient().when(statusService.isEnabled()).thenReturn(true);
        orchestrator = new AssistantOrchestrator(conversations, memory, promptFactory,
                toolRegistry, new AssistantCardAssembler(), modelClient,
                observation, new AssistantProviderErrorMapper(),
                statusService, new ProposalServiceImpl(proposals, metrics), metrics, clock);
    }

    @Test
    @DisplayName("rate-limited prose with observed search results still answers with cards")
    void observedSearchDataSurvivesRateLimit() {
        UUID eventId = UUID.randomUUID();
        SearchEventsResult observed = new SearchEventsResult(List.of(
                new EventSearchItem(eventId, "Hamlet", "THEATRE", "PUBLISHED",
                        Instant.parse("2026-09-17T16:00:00Z"),
                        new BigDecimal("10.00"), new BigDecimal("35.00"), "USD")));
        when(modelClient.execute(any())).thenAnswer(invocation -> {
            observation.recordSearchEvents(observed);
            throw new AssistantProviderException(AssistantChatErrorCode.AI_RATE_LIMITED,
                    "AI provider rate limit reached. Please try again shortly.");
        });

        AssistantChatResponse response = orchestrator.chat(null, "events next 2 weeks", "owner-1", toolContext);

        assertThat(response.error()).isNull();
        assertThat(response.state()).isEqualTo(AssistantState.DISCOVERING);
        assertThat(response.cards()).hasSize(1);
        // Fallback prose is short and never leaks internal IDs or tables.
        assertThat(response.assistantMessage()).doesNotContain(eventId.toString());
        assertThat(response.assistantMessage()).doesNotContain("|");
        // Successful fallback stores the turn like a normal answer.
        assertThat(memory.get(response.conversationId().toString())).hasSize(2);
    }

    @Test
    @DisplayName("provider failure with no observed tool data still returns a safe error")
    void emptyObservationStillErrors() {
        when(modelClient.execute(any())).thenThrow(new AssistantProviderException(
                AssistantChatErrorCode.AI_RATE_LIMITED, "AI provider rate limit reached."));

        AssistantChatResponse response = orchestrator.chat(null, "hi", "owner-1", toolContext);

        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(AssistantChatErrorCode.AI_RATE_LIMITED);
        assertThat(response.state()).isEqualTo(AssistantState.ERROR_RECOVERABLE);
    }
}
