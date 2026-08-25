package com.seatflow.event.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A single configured pricing tier")
public record PricingTierResponse(

    @Schema(description = "Pricing tier UUID") UUID id,
    @Schema(description = "Target section UUID") UUID sectionId,
    @Schema(description = "Pricing category name") String categoryName,
    @Schema(description = "Price amount") BigDecimal price,
    @Schema(description = "ISO-4217 currency code") String currency

) {}
