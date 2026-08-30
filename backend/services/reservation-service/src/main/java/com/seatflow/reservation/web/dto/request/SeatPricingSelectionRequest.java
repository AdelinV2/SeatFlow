package com.seatflow.reservation.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(description = "Ticket type selection for every seat in an active reservation")
public record SeatPricingSelectionRequest(
        @NotEmpty(message = "At least one seat pricing selection is required")
        @Size(max = 10, message = "A reservation can contain at most 10 seat pricing selections")
        List<@NotNull @Valid SeatPricingSelection> seats
) {

    @Schema(description = "Selected pricing tier for a held seat")
    public record SeatPricingSelection(
            @NotNull(message = "seatId is required") UUID seatId,
            @NotNull(message = "pricingTierId is required") UUID pricingTierId
    ) {
    }
}

