package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One customer-safe catalog hit inside {@link SearchEventsResult}.
 *
 * <p>Contains only fields necessary for discovery/recommendation. The current public catalog
 * contract exposes no venue identity on summary entries, so no venue is included (it must not be
 * invented). Every entry comes from the published catalog, hence {@code status} is the
 * authoritative upstream visibility guarantee.
 */
@Schema(description = "Customer-safe event catalog hit")
public record EventSearchItem(

        @Schema(description = "Event UUID for follow-up getEvent/getEventSessions calls") UUID eventId,
        @Schema(description = "Event title") String title,
        @Schema(description = "Catalog category") String category,
        @Schema(description = "Authoritative catalog visibility (always PUBLISHED here)") String status,
        @Schema(description = "Earliest future scheduled session start; display metadata, never a booking key")
        Instant nextSessionStartsAt,
        @Schema(description = "Lowest configured tier price, when exposed") BigDecimal minPrice,
        @Schema(description = "Highest configured tier price, when exposed") BigDecimal maxPrice,
        @Schema(description = "ISO-4217 currency of the price range, when exposed") String currency
) {}
