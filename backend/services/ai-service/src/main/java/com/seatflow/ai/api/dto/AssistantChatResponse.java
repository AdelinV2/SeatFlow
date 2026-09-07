package com.seatflow.ai.api.dto;

import com.seatflow.ai.orchestration.AssistantState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Typed, bounded chat response for {@code POST /api/ai/chat} (TASK-P15-004 section 3.1).
 *
 * <p>Application state is derived from validated orchestration/tool results; model text is
 * presentation only and cannot mutate card fields. {@code error} is present only for
 * recoverable/expired provider-side outcomes with a stable {@link AssistantChatErrorCode}.
 */
@Schema(description = "Assistant chat response with authoritative cards and application state")
public record AssistantChatResponse(

        @Schema(description = "Conversation UUID (server-generated on first turn)")
        UUID conversationId,

        @Schema(description = "Assistant presentation message; never contains secrets or reasoning")
        String assistantMessage,

        @Schema(description = "Canonical application state derived from tools, never free-form model text")
        AssistantState state,

        @Schema(description = "Authoritative cards built from validated tool results")
        List<AssistantCard> cards,

        @Schema(description = "Suggested next actions for the UI")
        List<String> suggestedActions,

        @Schema(description = "User-safe error with stable code; absent on success")
        AssistantError error
) {
    public AssistantChatResponse {
        cards = cards == null ? List.of() : List.copyOf(cards);
        suggestedActions = suggestedActions == null ? List.of() : List.copyOf(suggestedActions);
        assistantMessage = assistantMessage == null ? "" : assistantMessage;
    }
}
