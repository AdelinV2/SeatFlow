package com.seatflow.event.web.dto.response;

import com.seatflow.event.model.enums.EventCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Condensed public catalog event with aggregated price range")
public record EventSummaryResponse(

    @Schema(description = "Event UUID") UUID id,
    @Schema(description = "Event title") String title,
    @Schema(description = "Catalog category") EventCategory category,
    @Schema(description = "Public banner image URL") String bannerUrl,
    // P12-007 (ADR-011): derived display/search metadata, not a booking key.
    // nextSessionStartsAt is the earliest future SCHEDULED session start for this
    // event (null only when no visible session exists; published search filters
    // such events out). Booking must always select an explicit eventSessionId.
    @Schema(description = "Earliest future scheduled session start (display/search metadata, never a booking key)")
    Instant nextSessionStartsAt,
    @Schema(description = "Lowest configured tier price") BigDecimal minPrice,
    @Schema(description = "Highest configured tier price") BigDecimal maxPrice,
    @Schema(description = "Currency of the price range") String currency

) {}
