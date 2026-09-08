package com.seatflow.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stable API error codes for the AI chat contract (TASK-P15-004 section 10).
 *
 * <p>Provider {@code 429} fails fast to {@code AI_RATE_LIMITED}; invalid/deprecated model maps to
 * {@code AI_MODEL_UNAVAILABLE}; malformed tool arguments are rejected before any downstream call;
 * provider timeout/outage never affects core SeatFlow health; raw provider bodies/stack traces are
 * never returned; chain-of-thought/reasoning is never exposed.
 */
@Schema(description = "Stable AI chat error code")
public enum AssistantChatErrorCode {
    AI_DISABLED,
    AI_MISCONFIGURED,
    AI_RATE_LIMITED,
    AI_PROVIDER_TIMEOUT,
    AI_PROVIDER_UNAVAILABLE,
    AI_MODEL_UNAVAILABLE,
    AI_RESPONSE_INVALID
}
