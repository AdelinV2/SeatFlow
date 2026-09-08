package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import com.seatflow.common.observability.context.CorrelationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps provider/model failures to the stable TASK-P15-004 section 10 error contract without leaking
 * raw bodies, stack traces, keys, or reasoning.
 *
 * <ul>
 *   <li>provider {@code 401/403} -&gt; {@code AI_MISCONFIGURED} (invalid credentials, no retry);</li>
 *   <li>provider {@code 429} -&gt; {@code AI_RATE_LIMITED}, fail fast, no retry storm;</li>
 *   <li>invalid/deprecated model -&gt; {@code AI_MODEL_UNAVAILABLE};</li>
 *   <li>timeout -&gt; {@code AI_PROVIDER_TIMEOUT};</li>
 *   <li>outage/5xx/network -&gt; {@code AI_PROVIDER_UNAVAILABLE};</li>
 *   <li>malformed provider JSON / invalid tool-call payload / empty content -&gt;
 *       {@code AI_RESPONSE_INVALID} with safe fallback;</li>
 *   <li>malformed tool arguments are rejected before downstream calls (tool services);</li>
 *   <li>provider timeout/outage never affects core SeatFlow health;</li>
 *   <li>chain-of-thought/reasoning is never exposed to client/logs.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantProviderErrorMapper {

    private static final int MAX_ASSISTANT_MESSAGE_LENGTH = 2000;
    private static final String SAFE_ASSISTANT_FALLBACK =
            "I can help you discover events and seats. Tell me what you are looking for.";
    private static final Pattern SECRET_TOKEN = Pattern.compile(
            "(?i)\\b(?:gsk_|sk_(?:live|test)_|rk_(?:live|test)_|whsec_|re_)[A-Za-z0-9_-]{8,}");
    private static final Pattern OPAQUE_CREDENTIAL = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:sk-|pk-|rk-|key-|tok-|token-)[A-Za-z0-9_-]{12,}");
    private static final Pattern LABELED_CREDENTIAL = Pattern.compile(
            "(?i)\\b(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret|"
                    + "secret|password|credential|token)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b");
    private static final List<String> UNSAFE_PRESENTATION_MARKERS = List.of(
            "system prompt", "developer message", "chain of thought", "chain-of-thought",
            "chain_of_thought", "chainofthought", "reasoning", "reasoning_content",
            "developer prompt", "system instruction", "system instructions", "<system>",
            "<developer>", "<think>", "</think>", "<analysis>", "</analysis>", "analysis:",
            "tool_call", "tool call", "tool_calls", "function_call", "function call",
            "groq_api_key", "api key", "authorization", "bearer ", "jwt", "stripe secret",
            "database password", "postgres password", "internal credential");

    public AssistantChatErrorCode mapHttpStatus(int httpStatus, String safeHint) {
        if (httpStatus == 429) {
            return AssistantChatErrorCode.AI_RATE_LIMITED;
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return AssistantChatErrorCode.AI_MISCONFIGURED;
        }
        if (httpStatus == 404 && safeHint != null && safeHint.toLowerCase().contains("model")) {
            return AssistantChatErrorCode.AI_MODEL_UNAVAILABLE;
        }
        if (httpStatus == 400 && safeHint != null && safeHint.toLowerCase().contains("model")) {
            return AssistantChatErrorCode.AI_MODEL_UNAVAILABLE;
        }
        return AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE;
    }

    public AssistantChatErrorCode mapTimeout() {
        return AssistantChatErrorCode.AI_PROVIDER_TIMEOUT;
    }

    public AssistantChatErrorCode mapModelUnavailable() {
        return AssistantChatErrorCode.AI_MODEL_UNAVAILABLE;
    }

    public AssistantChatErrorCode mapResponseInvalid() {
        return AssistantChatErrorCode.AI_RESPONSE_INVALID;
    }

    public boolean isModelNotFoundMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("model_not_found") || lower.contains("model not found")
                || (lower.contains("model") && lower.contains("not found"))
                || (lower.contains("model") && lower.contains("deprecated"))
                || (lower.contains("model") && lower.contains("does not exist"));
    }

    /**
     * Classifies a provider-call failure into a stable {@link AssistantProviderException} whose
     * message is always user-safe (never the raw body, stack trace, key, or reasoning).
     *
     * <p>Order matters: rate-limit first (fail fast, no retry storm), then model identity, then
     * malformed payloads, then timeouts, then credentials, then the generic outage fallback.
     * Both the exception message and its immediate cause message are inspected because Spring AI
     * and HTTP clients commonly wrap the provider failure one level deep.
     */
    public AssistantProviderException classify(Exception ex) {
        String message = searchableText(ex);
        if (message.contains("429") || message.contains("rate limit") || message.contains("rate_limit")) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_RATE_LIMITED,
                    "AI provider rate limit reached. Please try again shortly.", ex);
        }
        if (isModelNotFoundMessage(message)
                || message.contains("model_unavailable") || message.contains("model unavailable")) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_MODEL_UNAVAILABLE,
                    "The configured AI model is unavailable. Core booking remains available.", ex);
        }
        if (isMalformedPayloadMessage(message)) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_RESPONSE_INVALID,
                    "The assistant returned an unreadable answer. Please try again.", ex);
        }
        if (message.contains("timed out") || message.contains("timeout")
                || message.contains("sockettimeout")) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_PROVIDER_TIMEOUT,
                    "AI provider timed out. Please try again shortly.", ex);
        }
        if (message.contains("401") || message.contains("403") || message.contains("unauthorized")
                || message.contains("invalid api key") || message.contains("invalid_api_key")) {
            return new AssistantProviderException(AssistantChatErrorCode.AI_MISCONFIGURED,
                    "AI provider credentials are invalid. AI is temporarily unavailable.", ex);
        }
        log.warn("AI provider call failed without leaking details: type={} correlationId={}",
                ex == null ? "unknown" : ex.getClass().getSimpleName(),
                CorrelationContext.getCorrelationId().orElse("N/A"));
        return new AssistantProviderException(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE,
                "AI provider is temporarily unavailable. Core booking remains available.", ex);
    }

    /**
     * Detects malformed provider output: broken JSON envelopes and structurally invalid tool-call
     * payloads. Matched on bounded lowercase text only; nothing is echoed back to callers.
     */
    boolean isMalformedPayloadMessage(String lower) {
        if (lower == null || lower.isBlank()) {
            return false;
        }
        boolean jsonBroken = lower.contains("json")
                && (lower.contains("pars") || lower.contains("malform")
                        || lower.contains("unexpected") || lower.contains("invalid")
                        || lower.contains("not valid"));
        boolean toolBroken = lower.contains("tool")
                && (lower.contains("pars") || lower.contains("malform")
                        || lower.contains("invalid") || lower.contains("schema")
                        || lower.contains("unexpected"));
        boolean emptyContent = (lower.contains("empty") || lower.contains("blank")
                || lower.contains("no content") || lower.contains("no text"))
                && (lower.contains("content") || lower.contains("response")
                        || lower.contains("message") || lower.contains("choice")
                        || lower.contains("completion"));
        return jsonBroken || toolBroken || emptyContent;
    }

    private static String searchableText(Exception ex) {
        StringBuilder text = new StringBuilder();
        appendMessage(text, ex == null ? null : ex.getMessage());
        if (ex != null && ex.getCause() != null && ex.getCause() != ex) {
            appendMessage(text, ex.getCause().getMessage());
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void appendMessage(StringBuilder text, String message) {
        if (message != null && !message.isBlank()) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(message);
        }
    }

    /**
     * Sanitizes model prose for presentation.
     *
     * <p>This is a fail-closed boundary: provider text that resembles a secret, credential,
     * system/developer prompt, hidden reasoning, or raw tool payload is replaced with a generic
     * safe response rather than being partially redacted and returned. The API layer must not
     * trust a provider to follow the prompt contract.
     */
    public String sanitizeAssistantMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return SAFE_ASSISTANT_FALLBACK;
        }
        String trimmed = stripControlCharacters(raw).trim();
        if (trimmed.isBlank() || containsUnsafePresentationContent(trimmed)) {
            return SAFE_ASSISTANT_FALLBACK;
        }
        return trimmed.length() > MAX_ASSISTANT_MESSAGE_LENGTH
                ? trimmed.substring(0, MAX_ASSISTANT_MESSAGE_LENGTH).trim()
                : trimmed;
    }

    private boolean containsUnsafePresentationContent(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return SECRET_TOKEN.matcher(text).find()
                || OPAQUE_CREDENTIAL.matcher(text).find()
                || LABELED_CREDENTIAL.matcher(text).find()
                || JWT.matcher(text).find()
                || UNSAFE_PRESENTATION_MARKERS.stream().anyMatch(lower::contains)
                || (lower.contains("arguments") && (lower.contains("function")
                        || lower.contains("\"name\"") || lower.startsWith("[")
                        || lower.startsWith("{") || lower.contains("```")))
                || (lower.contains("\"tool\"") && (lower.startsWith("{")
                        || lower.startsWith("[") || lower.contains("```")))
                || (lower.contains("\"function\"") && (lower.startsWith("{")
                        || lower.startsWith("[") || lower.contains("```")));
    }

    private static String stripControlCharacters(String raw) {
        StringBuilder safe = new StringBuilder(raw.length());
        raw.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t'
                    || !Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }
}
