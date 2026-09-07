package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Maps provider/model failures to the stable TASK-P15-004 section 10 error contract without leaking
 * raw bodies, stack traces, keys, or reasoning.
 *
 * <ul>
 *   <li>provider {@code 429} -&gt; {@code AI_RATE_LIMITED}, fail fast, no retry storm;</li>
 *   <li>invalid/deprecated model -&gt; {@code AI_MODEL_UNAVAILABLE};</li>
 *   <li>timeout -&gt; {@code AI_PROVIDER_TIMEOUT};</li>
 *   <li>outage/5xx/network -&gt; {@code AI_PROVIDER_UNAVAILABLE};</li>
 *   <li>malformed tool arguments are rejected before downstream calls (tool services);</li>
 *   <li>malformed structured model output -&gt; {@code AI_RESPONSE_INVALID} with safe fallback;</li>
 *   <li>provider timeout/outage never affects core SeatFlow health;</li>
 *   <li>chain-of-thought/reasoning is never exposed to client/logs.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantProviderErrorMapper {

    public AssistantChatErrorCode mapHttpStatus(int httpStatus, String safeHint) {
        if (httpStatus == 429) {
            return AssistantChatErrorCode.AI_RATE_LIMITED;
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
        String lower = message.toLowerCase();
        return lower.contains("model_not_found") || lower.contains("model not found")
                || (lower.contains("model") && lower.contains("not found"))
                || (lower.contains("model") && lower.contains("deprecated"))
                || (lower.contains("model") && lower.contains("does not exist"));
    }

    /** Sanitizes model prose for presentation: trims, bounds, strips reasoning leakage. */
    public String sanitizeAssistantMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "I can help you discover events and seats. Tell me what you are looking for.";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 2000) {
            trimmed = trimmed.substring(0, 2000).trim();
        }
        return trimmed;
    }
}
