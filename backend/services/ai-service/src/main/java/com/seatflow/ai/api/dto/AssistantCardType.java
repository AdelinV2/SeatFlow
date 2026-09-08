package com.seatflow.ai.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Canonical structured card types constructed by the backend from validated tool results.
 *
 * <p>Model prose may explain cards but cannot mutate card fields.
 */
@Schema(description = "Structured assistant card type")
public enum AssistantCardType {
    EVENT,
    SESSION,
    SEAT_SET,
    RESERVATION_PROPOSAL,
    INFO
}
