package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cohort rate with inspectable numerator/denominator.
 *
 * <p>{@code ratio} is a decimal in {@code [0, 1]} with up to 6 decimal places.
 * A zero denominator yields {@code ratio = null} (never NaN/Infinity) so callers
 * can distinguish "no cohort" from "zero rate".
 */
@Schema(description = "Cohort rate with inspectable numerator and denominator")
public record RateMetricResponse(
        @Schema(description = "Rate numerator (cohort members exhibiting the outcome)", example = "87")
        long numerator,
        @Schema(description = "Rate denominator (cohort size)", example = "120")
        long denominator,
        @Schema(description = "Decimal ratio 0..1 with up to 6 places; null when denominator is zero",
                example = "0.725", nullable = true)
        BigDecimal ratio) {

    public static RateMetricResponse of(long numerator, long denominator) {
        if (denominator == 0) {
            return new RateMetricResponse(numerator, 0L, null);
        }
        BigDecimal ratio = BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return new RateMetricResponse(numerator, denominator, ratio);
    }
}
