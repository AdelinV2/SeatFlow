package com.seatflow.ai.orchestration;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider exception classifier tests (TASK-P15-007 section 5): every simulated provider failure
 * maps to a stable SeatFlow category with a user-safe message that never echoes raw bodies,
 * stack traces, or secrets.
 */
class AssistantProviderExceptionClassifierTest {

    private final AssistantProviderErrorMapper mapper = new AssistantProviderErrorMapper();

    @ParameterizedTest(name = "''{0}'' -> {1}")
    @CsvSource({
            "'401 invalid key', AI_MISCONFIGURED",
            "'403 forbidden model permission', AI_MISCONFIGURED",
            "'unauthorized: invalid_api_key', AI_MISCONFIGURED",
            "'404 model_not_found', AI_MODEL_UNAVAILABLE",
            "'400 invalid model deprecated', AI_MODEL_UNAVAILABLE",
            "'The model does not exist', AI_MODEL_UNAVAILABLE",
            "'429 rate limited', AI_RATE_LIMITED",
            "'RateLimitError: rate_limit_exceeded', AI_RATE_LIMITED",
            "'500 provider error', AI_PROVIDER_UNAVAILABLE",
            "'502 Bad Gateway', AI_PROVIDER_UNAVAILABLE",
            "'503 provider unavailable', AI_PROVIDER_UNAVAILABLE",
            "'Connection refused', AI_PROVIDER_UNAVAILABLE",
            "'connect timeout', AI_PROVIDER_TIMEOUT",
            "'Read timed out', AI_PROVIDER_TIMEOUT",
            "'request timeout after 15s', AI_PROVIDER_TIMEOUT",
            "'malformed JSON: Unexpected token', AI_RESPONSE_INVALID",
            "'Failed to parse JSON response', AI_RESPONSE_INVALID",
            "'invalid tool-call payload: schema violation', AI_RESPONSE_INVALID",
            "'tool arguments malformed', AI_RESPONSE_INVALID",
            "'valid response with no expected content: empty choices', AI_RESPONSE_INVALID",
    })
    @DisplayName("provider failure text maps to the stable category")
    void failureTextMapsToCategory(String message, AssistantChatErrorCode expected) {
        AssistantProviderException classified = mapper.classify(new RuntimeException(message));

        assertThat(classified.getErrorCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("wrapped causes are inspected one level deep (timeouts behind HTTP clients)")
    void wrappedCausesInspected() {
        RuntimeException wrapped = new RuntimeException("request failed",
                new SocketTimeoutException("Read timed out"));

        assertThat(mapper.classify(wrapped).getErrorCode())
                .isEqualTo(AssistantChatErrorCode.AI_PROVIDER_TIMEOUT);
    }

    @Test
    @DisplayName("null/blank failures degrade to unavailable without throwing")
    void nullFailuresDegrade() {
        assertThat(mapper.classify(new RuntimeException((String) null)).getErrorCode())
                .isEqualTo(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE);
        assertThat(mapper.classify(null).getErrorCode())
                .isEqualTo(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("classified messages never echo raw bodies, keys, tokens, or reasoning")
    void classifiedMessagesAreSafe() {
        String evil = "401 body={\"key\":\"gsk_secret_xyz\"} Authorization: Bearer eyJhbGciOiJIUzI1NiJ9"
                + " reason: chain-of-thought leak stack=java.lang.RuntimeException: boom";
        AssistantProviderException classified = mapper.classify(new RuntimeException(evil));

        assertThat(classified.getErrorCode()).isEqualTo(AssistantChatErrorCode.AI_MISCONFIGURED);
        assertThat(classified.getMessage())
                .doesNotContain("gsk_secret_xyz")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .doesNotContain("chain-of-thought")
                .doesNotContain("RuntimeException: boom");
    }

    @Test
    @DisplayName("401/403 map to MISCONFIGURED at the HTTP-status layer")
    void httpStatusAuthMapsToMisconfigured() {
        assertThat(mapper.mapHttpStatus(401, "invalid key"))
                .isEqualTo(AssistantChatErrorCode.AI_MISCONFIGURED);
        assertThat(mapper.mapHttpStatus(403, "forbidden"))
                .isEqualTo(AssistantChatErrorCode.AI_MISCONFIGURED);
        assertThat(mapper.mapHttpStatus(404, "event not found"))
                .isEqualTo(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE);
        assertThat(mapper.mapHttpStatus(500, ""))
                .isEqualTo(AssistantChatErrorCode.AI_PROVIDER_UNAVAILABLE);
    }
}
