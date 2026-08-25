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
    @Schema(description = "UTC start time of the event") Instant eventDate,
    @Schema(description = "Lowest configured tier price") BigDecimal minPrice,
    @Schema(description = "Highest configured tier price") BigDecimal maxPrice,
    @Schema(description = "Currency of the price range") String currency

) {}
