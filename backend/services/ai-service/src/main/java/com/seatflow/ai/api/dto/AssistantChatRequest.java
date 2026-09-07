package com.seatflow.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Typed, bounded chat request for {@code POST /api/ai/chat} (TASK-P15-004 section 3.1).
 *
 * <p>{@code conversationId} is absent on the first turn; the server generates a UUID. {@code message}
 * is required and validated after trim to {@code 1..2000} characters by the orchestrator before any
 * provider call (a 2001-character message is rejected; 2000 is accepted after normalization).
 */
@Schema(description = "Assistant chat request; conversationId absent on first turn")
public record AssistantChatRequest(

        @Schema(description = "Existing conversation UUID; absent on first turn")
        UUID conversationId,

        @Schema(description = "User message; required, 1..2000 characters after trim", example = "Find 2 seats for Hamlet on Friday")
        String message
) {
}
