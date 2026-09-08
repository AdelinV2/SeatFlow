package com.seatflow.ai.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explicit allow-list for ordinary {@code /api/ai/chat} execution (TASK-P15-004 section 6,
 * TASK-P15-005 section 3).
 *
 * <p>Ordinary chat exposes exactly:
 * {@code searchEvents, getEvent, getEventSessions, getAvailableSeats, findBestSeats,
 * getReservation}.
 * {@code createReservation} is never registered in the ordinary chat set. No payment, refund,
 * admin analytics, staff scanner, user-management, arbitrary HTTP, SQL, filesystem, shell, Groq
 * browser search, Groq code execution, or remote MCP tool is exposed.
 *
 * <p>The registry is explicit, not component auto-discovery: a future bean cannot accidentally
 * become a chat tool simply because it has {@code @Tool}. The chat path passes only these
 * callbacks to the model. Enforcement points are this filtering plus
 * {@code AssistantToolRegistryTest} (a malicious {@code createReservation} bean stays excluded);
 * there is intentionally no separate assertion method that could report green while the filter does
 * the work.
 */
@Component
@RequiredArgsConstructor
public class AssistantToolRegistry {

    public static final Set<String> ORDINARY_CHAT_TOOLS = Set.of(
            "searchEvents",
            "getEvent",
            "getEventSessions",
            "getAvailableSeats",
            "findBestSeats",
            "getReservation");

    private final List<ToolCallbackProvider> toolCallbackProviders;

    /** Returns exactly the six P15-005 ordinary chat callbacks, in stable name order. */
    public List<ToolCallback> ordinaryChatToolCallbacks() {
        List<ToolCallback> all = toolCallbackProviders.stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toList();
        List<ToolCallback> allowed = all.stream()
                .filter(callback -> ORDINARY_CHAT_TOOLS.contains(callback.getToolDefinition().name()))
                .sorted((left, right) -> left.getToolDefinition().name()
                        .compareTo(right.getToolDefinition().name()))
                .toList();
        Set<String> names =
                allowed.stream().map(callback -> callback.getToolDefinition().name()).collect(Collectors.toSet());
        if (!names.equals(ORDINARY_CHAT_TOOLS)) {
            throw new IllegalStateException("Ordinary chat tool set must be exactly " + ORDINARY_CHAT_TOOLS
                    + " but was " + names);
        }
        return allowed;
    }

    /** Returns the allowed tool names in stable order (for assertions and prompts). */
    public Set<String> ordinaryChatToolNames() {
        return ORDINARY_CHAT_TOOLS;
    }
}
