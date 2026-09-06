package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * One stable x-axis point of an analytics time series.
 *
 * <p>{@code value} is a currency-neutral count for count metrics, or integer minor-unit money
 * for financial metrics (the enclosing series carries {@code currency}/{@code testMode}).
 * Missing dates are filled with zero so the x-axis is stable.
 */
@Schema(description = "One daily point of an analytics time series (zero-filled)")
public record AnalyticsDailyPointResponse(
        @Schema(description = "UTC calendar date", example = "2026-09-06") LocalDate date,
        @Schema(description = "Count, or minor-unit money for financial series", example = "174") long value) {
}
