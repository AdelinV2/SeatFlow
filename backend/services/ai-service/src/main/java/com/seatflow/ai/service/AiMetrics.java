package com.seatflow.ai.service;

import com.seatflow.ai.api.dto.AssistantChatErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded low-cardinality AI metrics (TASK-P15-007 section 10).
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code seatflow_ai_chat_requests_total{result}} — {@code success|error};</li>
 *   <li>{@code seatflow_ai_provider_requests_total{result}} —
 *       {@code success|rate_limited|timeout|unavailable|model_unavailable|invalid_response|misconfigured};</li>
 *   <li>{@code seatflow_ai_provider_latency_seconds} — provider call latency (no labels);</li>
 *   <li>{@code seatflow_ai_tool_calls_total{tool,result}} — {@code tool} is one of the six ordinary
 *       chat tools, {@code result} is {@code success|error};</li>
 *   <li>{@code seatflow_ai_proposals_total{result}} — {@code created|failed};</li>
 *   <li>{@code seatflow_ai_confirmations_total{result}} — {@code success|failure};</li>
 *   <li>{@code seatflow_ai_rate_limited_total{endpoint}} — {@code chat|confirm}.</li>
 * </ul>
 *
 * <p>Cardinality rules (enforced by construction, asserted by {@code AiMetricsTest}):
 * <ul>
 *   <li>tag keys are exactly {@code result}, {@code tool}, {@code endpoint} — never user ID,
 *       conversation ID, event/session/seat ID, prompt text, API key, model response, exception
 *       message, or model name;</li>
 *   <li>every tag value comes from a closed allow-list; anything else folds to {@code other};</li>
 *   <li>all recording failures are swallowed (metrics must never break a chat turn).</li>
 * </ul>
 */
@Slf4j
@Component
public class AiMetrics {

    public static final String CHAT_REQUESTS = "seatflow_ai_chat_requests_total";
    public static final String PROVIDER_REQUESTS = "seatflow_ai_provider_requests_total";
    public static final String PROVIDER_LATENCY = "seatflow_ai_provider_latency_seconds";
    public static final String TOOL_CALLS = "seatflow_ai_tool_calls_total";
    public static final String PROPOSALS = "seatflow_ai_proposals_total";
    public static final String CONFIRMATIONS = "seatflow_ai_confirmations_total";
    public static final String RATE_LIMITED = "seatflow_ai_rate_limited_total";

    /** The only tag keys this component ever emits. */
    public static final Set<String> TAG_KEYS = Set.of("result", "tool", "endpoint");

    static final Set<String> CHAT_RESULTS = Set.of("success", "error");
    static final Set<String> PROVIDER_RESULTS = Set.of(
            "success", "rate_limited", "timeout", "unavailable",
            "model_unavailable", "invalid_response", "misconfigured");
    static final Set<String> TOOLS = Set.of(
            "searchEvents", "getEvent", "getEventSessions",
            "getAvailableSeats", "findBestSeats", "getReservation");
    static final Set<String> TOOL_RESULTS = Set.of("success", "error");
    static final Set<String> PROPOSAL_RESULTS = Set.of("created", "failed");
    static final Set<String> CONFIRMATION_RESULTS = Set.of("success", "failure");
    static final Set<String> RATE_LIMIT_ENDPOINTS = Set.of("chat", "confirm");

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Timer providerLatency;

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.providerLatency = Timer.builder(PROVIDER_LATENCY)
                .description("AI provider call latency")
                .register(registry);
    }

    /** Maps a stable chat error code to a bounded provider result label. */
    public static String providerResult(AssistantChatErrorCode code) {
        if (code == null) {
            return "success";
        }
        return switch (code) {
            case AI_RATE_LIMITED -> "rate_limited";
            case AI_PROVIDER_TIMEOUT -> "timeout";
            case AI_PROVIDER_UNAVAILABLE -> "unavailable";
            case AI_MODEL_UNAVAILABLE -> "model_unavailable";
            case AI_RESPONSE_INVALID -> "invalid_response";
            case AI_MISCONFIGURED, AI_DISABLED -> "misconfigured";
        };
    }

    public void recordChatRequest(boolean success) {
        increment(CHAT_REQUESTS, Map.of("result", success ? "success" : "error"));
    }

    public void recordProviderRequest(String result) {
        increment(PROVIDER_REQUESTS, Map.of("result", allowed(result, PROVIDER_RESULTS)));
    }

    public void recordProviderLatency(Duration duration) {
        try {
            providerLatency.record(duration);
        } catch (RuntimeException ex) {
            log.warn("Failed to record AI provider latency metric", ex);
        }
    }

    public void recordToolCall(String tool, boolean success) {
        increment(TOOL_CALLS, Map.of(
                "tool", allowed(tool, TOOLS),
                "result", success ? "success" : "error"));
    }

    public void recordProposal(boolean created) {
        increment(PROPOSALS, Map.of("result", created ? "created" : "failed"));
    }

    public void recordConfirmation(boolean success) {
        increment(CONFIRMATIONS, Map.of("result", success ? "success" : "failure"));
    }

    public void recordRateLimited(String endpoint) {
        increment(RATE_LIMITED, Map.of("endpoint", allowed(endpoint, RATE_LIMIT_ENDPOINTS)));
    }

    private void increment(String name, Map<String, String> tags) {
        try {
            String key = name + tags;
            counters.computeIfAbsent(key, ignored -> {
                Counter.Builder builder = Counter.builder(name)
                        .description("SeatFlow AI assistant metric");
                tags.forEach(builder::tag);
                return builder.register(registry);
            }).increment();
        } catch (RuntimeException ex) {
            log.warn("Failed to record AI metric: name={}", name, ex);
        }
    }

    private static String allowed(String value, Set<String> allowed) {
        if (value != null && allowed.contains(value)) {
            return value;
        }
        return "other";
    }
}
