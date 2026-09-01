package com.seatflow.payment.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Billing address used for a Stripe Tax preview")
public record TaxPreviewRequest(
        @NotBlank String line1,
        String line2,
        @NotBlank String city,
        String state,
        @NotBlank String postalCode,
        @NotBlank String country
) {
}

