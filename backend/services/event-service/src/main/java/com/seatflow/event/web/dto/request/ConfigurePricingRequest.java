package com.seatflow.event.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Request body for configuring the pricing tiers of an event")
public record ConfigurePricingRequest(

    @Schema(description = "Ordered list of pricing tiers for the event", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "At least one pricing tier is required")
    @Size(max = 200, message = "A maximum of 200 pricing tiers is allowed")
    @Valid
    List<PricingTierItemRequest> pricingTiers

) {}
