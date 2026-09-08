package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import com.seatflow.ai.service.AiMetrics;
import com.seatflow.common.observability.context.CorrelationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Groq-backed model client via Spring AI {@code ChatClient} (TASK-P15-004).
 *
 * <p>Uses the explicit P15-004 allow-list (never auto-discovery), the versioned system prompt, and
 * the caller-supplied bounded history (roles preserved). The request contains only sanitized data:
 * system prompt, bounded prior-turn messages, trimmed user message, and the allow-list names. It
 * never contains the {@code GROQ_API_KEY}, Authorization/Bearer JWTs, Stripe/payment secrets, DB
 * credentials, stack traces, unrelated PII, entire upstream payloads, lock keys, or owner metadata.
 *
 * <p>Single memory-write ownership: this client never persists to {@code ChatMemory} (no memory
 * advisor). The orchestrator owns all writes — exactly one user/assistant pair per successful turn,
 * nothing on provider failure — so production stores the same 2-messages-per-turn shape the mocked
 * tests assert. Tool results are observed separately via {@link AssistantToolObservation}
 * (authoritative DTOs); this client returns presentation prose. Chain-of-thought/reasoning is never
 * returned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatClientAssistantModelClient implements AssistantModelClient {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final AssistantToolRegistry toolRegistry;
    private final AssistantProviderErrorMapper errorMapper;
    private final AiMetrics metrics;

    @Override
    public ModelTurnResult execute(ModelTurnRequest request) {
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            log.warn("AI_PROVIDER_UNAVAILABLE scope=provider conversationId={} correlationId={}",
                    request.conversationId(), correlationId());
            throw new AssistantProviderException(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "AI provider is temporarily unavailable. Core booking remains available.");
        }
        try {
            String content = callForContent(builder, request);
            if (content == null || content.isBlank()) {
                // Transient empty completions (stop with no text and no tool calls) happen:
                // one bounded same-request retry, then the safe invalid-response fallback.
                // This never retries rate limits (those throw before reaching here).
                log.info("AI returned blank content, retrying once: conversationId={} correlationId={}",
                        request.conversationId(), correlationId());
                content = callForContent(builder, request);
            }
            if (content == null || content.isBlank()) {
                throw new AssistantProviderException(AssistantChatErrorCode.AI_RESPONSE_INVALID,
                        "The assistant returned an unreadable answer. Please try again.");
            }
            String safe = errorMapper.sanitizeAssistantMessage(content);
            return new ModelTurnResult(safe, null, null, null, null, null);
        } catch (AssistantProviderException ex) {
            logProviderEvent(ex.getErrorCode(), request.conversationId());
            throw ex;
        } catch (Exception ex) {
            AssistantProviderException mapped = mapProviderFailure(ex);
            logProviderEvent(mapped.getErrorCode(), request.conversationId());
            throw mapped;
        }
    }

    private String callForContent(ChatClient.Builder builder, ModelTurnRequest request) {
        Instant started = Instant.now();
        try {
            String content = builder.build().prompt()
                    .system(request.systemPrompt())
                    .messages(request.history())
                    .user(request.userMessage())
                    .toolCallbacks(toolRegistry.ordinaryChatToolCallbacks()
                            .toArray(new org.springframework.ai.tool.ToolCallback[0]))
                    .call()
                    .content();
            metrics.recordProviderRequest(content == null || content.isBlank()
                    ? AiMetrics.providerResult(AssistantChatErrorCode.AI_RESPONSE_INVALID)
                    : "success");
            return content;
        } catch (AssistantProviderException ex) {
            metrics.recordProviderRequest(AiMetrics.providerResult(ex.getErrorCode()));
            throw ex;
        } catch (Exception ex) {
            AssistantProviderException mapped = mapProviderFailure(ex);
            metrics.recordProviderRequest(AiMetrics.providerResult(mapped.getErrorCode()));
            throw mapped;
        } finally {
            metrics.recordProviderLatency(Duration.between(started, Instant.now()));
        }
    }

    private void logProviderEvent(AssistantChatErrorCode code, String conversationId) {
        switch (code) {
            case AI_RATE_LIMITED ->
                    log.warn("AI_PROVIDER_RATE_LIMITED scope=provider conversationId={} correlationId={}",
                            conversationId, correlationId());
            case AI_PROVIDER_TIMEOUT ->
                    log.warn("AI provider timeout: conversationId={} correlationId={}",
                            conversationId, correlationId());
            case AI_MODEL_UNAVAILABLE ->
                    log.warn("AI model unavailable: conversationId={} correlationId={}",
                            conversationId, correlationId());
            case AI_RESPONSE_INVALID ->
                    log.warn("AI response invalid: conversationId={} correlationId={}",
                            conversationId, correlationId());
            case AI_MISCONFIGURED ->
                    log.warn("AI provider misconfigured: conversationId={} correlationId={}",
                            conversationId, correlationId());
            default ->
                    log.warn("AI_PROVIDER_UNAVAILABLE scope=provider conversationId={} correlationId={}",
                            conversationId, correlationId());
        }
    }

    private static String correlationId() {
        return CorrelationContext.getCorrelationId().orElse("N/A");
    }

    private AssistantProviderException mapProviderFailure(Exception ex) {
        return errorMapper.classify(ex);
    }
}
