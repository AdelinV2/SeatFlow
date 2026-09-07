package com.seatflow.ai.orchestration;

import com.seatflow.ai.context.AiRequestContext;
import com.seatflow.ai.service.AiStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Concurrency tests (TASK-P15-004 section 12; mandatory 12).
 */
@ExtendWith(MockitoExtension.class)
class AssistantConcurrencyTest {

    @Mock
    private AssistantPromptFactory promptFactory;
    @Mock
    private AssistantToolRegistry toolRegistry;
    @Mock
    private AssistantModelClient modelClient;
    @Mock
    private AiStatusService statusService;

    @Test
    @DisplayName("12: parallel turns for the same conversation are ordered/rejected safely")
    void parallelTurnsSerialized() throws Exception {
        Clock clock = Clock.systemUTC();
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        var conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        var orchestrator = new AssistantOrchestrator(conversations, memory, promptFactory,
                toolRegistry, new AssistantCardAssembler(), modelClient,
                new AssistantToolObservation(), new AssistantProviderErrorMapper(),
                statusService, clock);

        when(promptFactory.systemPrompt()).thenReturn("prompt");
        when(statusService.isChatAvailable()).thenReturn(true);
        org.mockito.Mockito.lenient().when(statusService.isEnabled()).thenReturn(true);
        java.util.concurrent.atomic.AtomicInteger concurrent = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxConcurrent = new java.util.concurrent.atomic.AtomicInteger();
        when(modelClient.execute(any())).thenAnswer(invocation -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(300);
            } finally {
                concurrent.decrementAndGet();
            }
            return new AssistantModelClient.ModelTurnResult("done", null, null, null, null, null);
        });

        var first = orchestrator.chat(null, "hello", "owner-1",
                new AiRequestContext("bearer", "corr", "owner-1"));
        UUID conversationId = first.conversationId();

        int contenders = 4;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    orchestrator.chat(conversationId, "parallel " + index, "owner-1",
                            new AiRequestContext("bearer", "corr", "owner-1"));
                    ok.incrementAndGet();
                } catch (com.seatflow.common.domain.exception.ConflictException ex) {
                    busy.incrementAndGet();
                } catch (Exception ex) {
                    busy.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // Safe outcome: no parallel model calls (single-flight), all turns either ordered or
        // busy-rejected, never contradictory. With a 5s bounded wait the contenders queue and all
        // succeed sequentially; with a shorter wait some would get 409. Both are safe.
        assertThat(maxConcurrent.get()).isEqualTo(1);
        assertThat(ok.get() + busy.get()).isEqualTo(contenders);
        assertThat(ok.get()).isGreaterThanOrEqualTo(1);
        var stored = conversations.getForOwner(conversationId, "owner-1");
        assertThat(stored).isNotNull();
    }

    @Test
    @DisplayName("duplicate retry with same constraints does not create contradictory drafts")
    void duplicateRetryIdempotent() {
        Clock clock = Clock.systemUTC();
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(24)
                .build();
        var conversations = new ConversationStore(
                new AssistantConversationProperties(24, Duration.ofMinutes(30), 500), clock, memory);
        var orchestrator = new AssistantOrchestrator(conversations, memory, promptFactory,
                toolRegistry, new AssistantCardAssembler(), modelClient,
                new AssistantToolObservation(), new AssistantProviderErrorMapper(),
                statusService, clock);

        when(promptFactory.systemPrompt()).thenReturn("prompt");
        when(statusService.isChatAvailable()).thenReturn(true);
        org.mockito.Mockito.lenient().when(statusService.isEnabled()).thenReturn(true);
        UUID sessionId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        var candidate = new com.seatflow.ai.tool.dto.FindBestSeatsResult.SeatCandidate(List.of(seatId),
                List.of(new com.seatflow.ai.tool.dto.AvailableSeatItem(seatId, UUID.randomUUID(), "S", "A", 1,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "STD", UUID.randomUUID(),
                        1000L, "EUR", "AVAILABLE")),
                1000L, "EUR", true, List.of("r"), 1);
        var best = new com.seatflow.ai.tool.dto.FindBestSeatsResult(sessionId,
                java.time.Instant.now(), "OK", List.of(candidate), List.of(), List.of());
        when(modelClient.execute(any())).thenReturn(
                new AssistantModelClient.ModelTurnResult("prose", null, null, null, null, best));

        var context = new AiRequestContext("bearer", "corr", "owner-1");
        var first = orchestrator.chat(null, "1 seat", "owner-1", context);
        var second = orchestrator.chat(first.conversationId(), "1 seat", "owner-1", context);

        UUID firstDraft = first.cards().stream()
                .filter(card -> card.type().name().equals("RESERVATION_PROPOSAL"))
                .findFirst().orElseThrow().proposalId();
        UUID secondDraft = second.cards().stream()
                .filter(card -> card.type().name().equals("RESERVATION_PROPOSAL"))
                .findFirst().orElseThrow().proposalId();
        // Same fingerprint reuses the same draft (idempotent retry, no contradiction).
        assertThat(secondDraft).isEqualTo(firstDraft);
    }
}
