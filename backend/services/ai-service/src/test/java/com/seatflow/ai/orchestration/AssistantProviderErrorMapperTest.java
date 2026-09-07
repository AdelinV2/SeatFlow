package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider error contract tests (TASK-P15-004 section 10; mandatory 13).
 */
class AssistantProviderErrorMapperTest {

    private final AssistantProviderErrorMapper mapper = new AssistantProviderErrorMapper();

    @Test
    @DisplayName("429 fails fast to AI_RATE_LIMITED")
    void rateLimited() {
        assertThat(mapper.mapHttpStatus(429, "")).isEqualTo(AssistantChatErrorCode.AI_RATE_LIMITED);
    }

    @Test
    @DisplayName("invalid/deprecated model maps to AI_MODEL_UNAVAILABLE")
    void modelUnavailable() {
        assertThat(mapper.mapHttpStatus(404, "model_not_found")).isEqualTo(
                AssistantChatErrorCode.AI_MODEL_UNAVAILABLE);
        assertThat(mapper.mapHttpStatus(400, "model deprecated")).isEqualTo(
                AssistantChatErrorCode.AI_MODEL_UNAVAILABLE);
        assertThat(mapper.mapModelUnavailable()).isEqualTo(AssistantChatErrorCode.AI_MODEL_UNAVAILABLE);
        assertThat(mapper.isModelNotFoundMessage("The model does not exist")).isTrue();
    }

    @Test
    @DisplayName("timeout maps to AI_PROVIDER_TIMEOUT; 5xx to AI_PROVIDER_UNAVAILABLE")
    void timeoutAndUnavailable() {
        assertThat(mapper.mapTimeout()).isEqualTo(AssistantChatErrorCode.AI_PROVIDER_TIMEOUT);
        assertThat(mapper.mapHttpStatus(500, "")).isEqualTo(
                AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE);
        assertThat(mapper.mapHttpStatus(503, "")).isEqualTo(
                AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("assistant prose sanitized, bounded, never null")
    void sanitize() {
        assertThat(mapper.sanitizeAssistantMessage(null)).isNotBlank();
        assertThat(mapper.sanitizeAssistantMessage("   ")).isNotBlank();
        assertThat(mapper.sanitizeAssistantMessage("x".repeat(5000))).hasSizeLessThanOrEqualTo(2000);
    }
}
