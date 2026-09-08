package com.seatflow.ai.service;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Low-cardinality AI metrics tests (TASK-P15-007 section 10).
 */
class AiMetricsTest {

    private MeterRegistry registry;
    private AiMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiMetrics(registry);
    }

    @Test
    @DisplayName("chat requests record bounded success/error results")
    void chatRequests() {
        metrics.recordChatRequest(true);
        metrics.recordChatRequest(false);

        assertThat(count(AiMetrics.CHAT_REQUESTS, "result", "success")).isEqualTo(1.0);
        assertThat(count(AiMetrics.CHAT_REQUESTS, "result", "error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("provider requests record the stable result categories")
    void providerRequests() {
        metrics.recordProviderRequest("success");
        metrics.recordProviderRequest("rate_limited");
        metrics.recordProviderRequest("timeout");
        metrics.recordProviderRequest("unavailable");
        metrics.recordProviderRequest("model_unavailable");
        metrics.recordProviderRequest("invalid_response");
        metrics.recordProviderRequest("misconfigured");

        for (String result : AiMetrics.PROVIDER_RESULTS) {
            assertThat(count(AiMetrics.PROVIDER_REQUESTS, "result", result))
                    .as("provider result %s", result)
                    .isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("provider latency timer records without labels")
    void providerLatency() {
        metrics.recordProviderLatency(java.time.Duration.ofMillis(150));

        assertThat(registry.get(AiMetrics.PROVIDER_LATENCY).timer().count()).isEqualTo(1L);
        assertThat(registry.get(AiMetrics.PROVIDER_LATENCY).timer().getId().getTags()).isEmpty();
    }

    @Test
    @DisplayName("tool calls record per-tool success/error with tool names only")
    void toolCalls() {
        for (String tool : AiMetrics.TOOLS) {
            metrics.recordToolCall(tool, true);
            metrics.recordToolCall(tool, false);
        }

        assertThat(count2(AiMetrics.TOOL_CALLS, "tool", "findBestSeats", "result", "success"))
                .isEqualTo(1.0);
        assertThat(count2(AiMetrics.TOOL_CALLS, "tool", "searchEvents", "result", "error"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("proposals/confirmations/rate-limited counters use bounded labels")
    void lifecycleCounters() {
        metrics.recordProposal(true);
        metrics.recordProposal(false);
        metrics.recordConfirmation(true);
        metrics.recordConfirmation(false);
        metrics.recordRateLimited("chat");
        metrics.recordRateLimited("confirm");

        assertThat(count(AiMetrics.PROPOSALS, "result", "created")).isEqualTo(1.0);
        assertThat(count(AiMetrics.PROPOSALS, "result", "failed")).isEqualTo(1.0);
        assertThat(count(AiMetrics.CONFIRMATIONS, "result", "success")).isEqualTo(1.0);
        assertThat(count(AiMetrics.CONFIRMATIONS, "result", "failure")).isEqualTo(1.0);
        assertThat(count(AiMetrics.RATE_LIMITED, "endpoint", "chat")).isEqualTo(1.0);
        assertThat(count(AiMetrics.RATE_LIMITED, "endpoint", "confirm")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("unknown tag values fold to 'other' instead of exploding cardinality")
    void unknownValuesFoldToOther() {
        metrics.recordProviderRequest("user-123-conversation-xyz");
        metrics.recordToolCall("createReservation", true);
        metrics.recordRateLimited("user-123");

        assertThat(count(AiMetrics.PROVIDER_REQUESTS, "result", "other")).isEqualTo(1.0);
        assertThat(count2(AiMetrics.TOOL_CALLS, "tool", "other", "result", "success"))
                .isEqualTo(1.0);
        assertThat(count(AiMetrics.RATE_LIMITED, "endpoint", "other")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("no meter ever carries a high-cardinality or secret-bearing tag key")
    void onlyLowCardinalityTagKeys() {
        metrics.recordChatRequest(true);
        metrics.recordProviderRequest("success");
        metrics.recordProviderLatency(java.time.Duration.ofMillis(5));
        metrics.recordToolCall("searchEvents", true);
        metrics.recordProposal(true);
        metrics.recordConfirmation(true);
        metrics.recordRateLimited("chat");

        registry.forEachMeter(meter -> assertThat(meter.getId().getTags())
                .as("tags of %s", meter.getId().getName())
                .allSatisfy(tag -> assertThat(AiMetrics.TAG_KEYS)
                        .as("tag key %s on %s", tag.getKey(), meter.getId().getName())
                        .contains(tag.getKey())));
    }

    @Test
    @DisplayName("every chat error code maps to a bounded provider result")
    void errorCodesMapToBoundedResults() {
        for (AssistantChatErrorCode code : AssistantChatErrorCode.values()) {
            assertThat(AiMetrics.PROVIDER_RESULTS)
                    .as("mapping for %s", code)
                    .contains(AiMetrics.providerResult(code));
        }
        assertThat(AiMetrics.providerResult(null)).isEqualTo("success");
    }

    private double count(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    private double count2(String name, String key1, String value1, String key2, String value2) {
        return registry.get(name).tags(key1, value1, key2, value2).counter().count();
    }
}
