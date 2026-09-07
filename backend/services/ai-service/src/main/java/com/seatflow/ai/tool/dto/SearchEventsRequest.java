package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Typed input for the {@code searchEvents} AI tool.
 *
 * <p>All criteria are optional: empty criteria map to the existing public upcoming-events catalog
 * behavior. Date bounds apply to the authoritative {@code nextSessionStartsAt} display metadata
 * of each catalog entry (never to a legacy event date).
 */
@Schema(description = "Criteria for read-only customer event discovery")
public record SearchEventsRequest(

        @Schema(description = "Free-text title search; blank maps to absent; at most 100 characters",
                example = "Hamlet")
        String query,

        @Schema(description = "Public catalog category (CONCERT, THEATRE, SPORTS, CONFERENCE, OTHER)",
                example = "THEATRE")
        String category,

        @Schema(description = "Inclusive user-facing lower bound for the next session date",
                example = "2026-09-10")
        LocalDate startDate,

        @Schema(description = "Inclusive user-facing upper bound; must be on or after startDate",
                example = "2026-09-30")
        LocalDate endDate,

        @Schema(description = "Maximum results; server-clamped to 1..20, defaults to 5", example = "5")
        Integer limit
) {}
