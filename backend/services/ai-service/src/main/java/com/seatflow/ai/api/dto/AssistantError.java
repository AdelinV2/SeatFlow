package com.seatflow.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Bounded, user-safe error payload inside {@code AssistantChatResponse}.
 *
 * <p>Carries one stable {@link AssistantChatErrorCode} plus a safe human-readable message. Raw
 * provider bodies, stack traces, keys, tokens, and chain-of-thought are never included.
 */
@Schema(description = "User-safe AI chat error; absent on success")
public record AssistantError(

        @Schema(description = "Stable machine-readable AI error code", example = "AI_RATE_LIMITED")
        AssistantChatErrorCode code,

        @Schema(description = "Safe human-readable message; never contains secrets or stack traces")
        String message
) {
    public AssistantError {
        if (code == null) {
            throw new IllegalArgumentException("Assistant error code is required");
        }
        message = message == null ? "" : message;
    }
}
