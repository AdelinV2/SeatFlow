package com.seatflow.event.web.dto.response;

import com.seatflow.event.model.enums.EventCategory;
import com.seatflow.event.model.enums.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Full event detail including pricing tiers")
public record EventDetailResponse(

    @Schema(description = "Event UUID") UUID id,
    @Schema(description = "Owning venue UUID") UUID venueId,
    @Schema(description = "Event title") String title,
    @Schema(description = "Full event description") String description,
    @Schema(description = "Catalog category") EventCategory category,
    @Schema(description = "Public banner image URL") String bannerUrl,
    @Schema(description = "UTC start time of the event") Instant eventDate,
    @Schema(description = "Lifecycle status") EventStatus status,
    @Schema(description = "Configured pricing tiers") List<PricingTierResponse> pricingTiers,
    @Schema(description = "Creation timestamp") Instant createdAt,
    @Schema(description = "Last update timestamp") Instant updatedAt

) {}
