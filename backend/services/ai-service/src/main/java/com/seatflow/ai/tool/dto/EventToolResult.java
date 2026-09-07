package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Compact customer-safe event representation returned by the {@code getEvent} AI tool.
 *
 * <p>Upstream status semantics are preserved verbatim; bookability is never inferred from prose.
 * The current public Event Service contract exposes {@code venueId} but no venue name, so no
 * venue name is included. Internal fields (audit timestamps, version columns, raw entities) are
 * intentionally absent.
 */
@Schema(description = "Authoritative customer-safe snapshot of one published event")
public record EventToolResult(

        @Schema(description = "Event UUID") UUID eventId,
        @Schema(description = "Event title") String title,
        @Schema(description = "Bounded public description (truncated when long)") String descriptionSummary,
        @Schema(description = "Catalog category") String category,
        @Schema(description = "Owning venue UUID from the public contract") UUID venueId,
        @Schema(description = "Authoritative lifecycle status from Event Service") String status
) {}
