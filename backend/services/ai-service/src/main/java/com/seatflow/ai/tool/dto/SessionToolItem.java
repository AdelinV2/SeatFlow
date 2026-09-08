package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One booking-visible session inside {@link EventSessionsToolResult}.
 *
 * <p>Timestamps are the authoritative Event Service instants preserved exactly. The session ID is
 * the only valid inventory partition key for later seat tools. {@code bookableHint} is derived
 * solely from authoritative status/sales-window data, never from model judgment.
 */
@Schema(description = "Customer-safe snapshot of one booking-visible event session")
public record SessionToolItem(

        @Schema(description = "Session UUID: the inventory partition key for later seat tools")
        UUID eventSessionId,
        @Schema(description = "Authoritative session start instant") Instant startsAt,
        @Schema(description = "Authoritative session end instant, when set") Instant endsAt,
        @Schema(description = "Authoritative session lifecycle status") String status,
        @Schema(description = "Public sales-open instant, when exposed") Instant salesStartAt,
        @Schema(description = "Public sales-close instant, when exposed") Instant salesEndAt,
        @Schema(description = "BOOKABLE, SALES_CLOSED, or NOT_BOOKABLE from authoritative data")
        String bookableHint
) {}
