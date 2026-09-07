package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

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

    @Override
    public ModelTurnResult execute(ModelTurnRequest request) {
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            log.warn("AI chat model unavailable: no ChatClient builder, conversationId={}",
                    request.conversationId());
            throw new AssistantProviderException(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "AI provider is temporarily unavailable. Core booking remains available.");
        }
        try {
            String content = builder.build().prompt()
                    .system(request.systemPrompt())
                    .messages(request.history())
                    .user(request.userMessage())
                    .toolCallbacks(toolRegistry.ordinaryChatToolCallbacks()
                            .toArray(new org.springframework.ai.tool.ToolCallback[0]))
                    .call()
                    .content();
            String safe = errorMapper.sanitizeAssistantMessage(content);
            return new ModelTurnResult(safe, null, null, null, null, null);
        } catch (AssistantProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw mapProviderFailure(ex);
        }
    }

    private AssistantProviderException mapProviderFailure(Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit")) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_RATE_LIMITED,
                    "AI provider rate limit reached. Please try again shortly.", ex);
        }
        if (errorMapper.isModelNotFoundMessage(message)
                || lower.contains("model_unavailable") || lower.contains("model unavailable")) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_MODEL_UNAVAILABLE,
                    "The configured AI model is unavailable. Core booking remains available.", ex);
        }
        if (lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("read timed out") || lower.contains("connect timed out")) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_PROVIDER_TIMEOUT,
                    "AI provider timed out. Please try again shortly.", ex);
        }
        if (lower.contains("401") || lower.contains("403") || lower.contains("unauthorized")
                || lower.contains("invalid api key") || lower.contains("invalid_api_key")) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_MISCONFIGURED,
                    "AI provider credentials are invalid. AI is temporarily unavailable.", ex);
        }
        log.warn("AI provider call failed without leaking details: {}", ex.getClass().getSimpleName());
        return new AssistantProviderException(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE,
                "AI provider is temporarily unavailable. Core booking remains available.", ex);
    }
}
