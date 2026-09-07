package com.seatflow.ai.tool.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One available seat inside an {@code AvailableSeatsResult} snapshot. Only {@code AVAILABLE} +
 * active seats are ever returned; every entry carries its resolved single-currency price.
 */
@Schema(description = "One available seat with its resolved price")
public record AvailableSeatItem(

        @Schema(description = "Seat UUID") UUID seatId,
        @Schema(description = "Owning section UUID") UUID sectionId,
        @Schema(description = "Owning section name") String sectionName,
        @Schema(description = "Human-readable row label") String rowLabel,
        @Schema(description = "Seat number within the row") int seatNumber,
        @Schema(description = "Seat position X in venue-global coordinates, when derivable")
        java.math.BigDecimal positionX,
        @Schema(description = "Seat position Y in venue-global coordinates, when derivable")
        java.math.BigDecimal positionY,
        @Schema(description = "Resolved pricing category") String categoryName,
        @Schema(description = "Resolved pricing tier UUID for later reservation reconciliation")
        UUID pricingTierId,
        @Schema(description = "Exact price in minor units") long priceMinor,
        @Schema(description = "ISO-4217 currency code") String currency,
        @Schema(description = "Live seat status; always AVAILABLE in this result") String status
) {}
