package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Currency-isolated money cell in integer minor units.
 *
 * <p>Money is never a mixed-currency scalar: one item per currency with
 * {@code netMinor = grossMinor - refundedMinor} computed exactly. All analytics revenue is
 * Stripe Test Mode demo data, hence {@code testMode} is always {@code true}.
 */
@Schema(description = "Currency-isolated revenue in integer minor units (Stripe Test Mode demo data)")
public record MoneyMetricResponse(
        @Schema(description = "ISO 4217 currency code", example = "RON")
        String currency,
        @Schema(description = "Completed gross revenue in minor units", example = "3150000")
        long grossMinor,
        @Schema(description = "Completed-refund revenue in minor units", example = "120000")
        long refundedMinor,
        @Schema(description = "Exact net revenue (gross minus refunded) in minor units", example = "3030000")
        long netMinor,
        @Schema(description = "Always true: analytics revenue is Stripe Test Mode demo data", example = "true")
        boolean testMode) {

    public static MoneyMetricResponse of(String currency, long grossMinor, long refundedMinor) {
        return new MoneyMetricResponse(currency, grossMinor, refundedMinor,
                grossMinor - refundedMinor, true);
    }
}
