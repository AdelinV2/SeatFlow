package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily time series for {@code GET /api/admin/analytics/timeseries} (TASK-P14-004 §6.3).
 *
 * <p>Only {@code DAY} granularity is supported in Phase 14. Money metrics produce one series
 * per currency; count metrics produce exactly one currency-neutral series (never duplicated
 * once per currency). At most 366 points per series; missing dates are zero-filled.
 */
@Schema(description = "Daily analytics time series (DAY granularity only)")
public record AnalyticsTimeSeriesResponse(
        @Schema(description = "Requested metric", example = "GROSS_REVENUE") String metric,
        @Schema(description = "Inclusive range start (UTC)") LocalDate from,
        @Schema(description = "Inclusive range end (UTC)") LocalDate to,
        @Schema(description = "One series per currency for money metrics; a single series for counts")
        List<Series> series) {

    @Schema(description = "One currency-neutral or single-currency daily series")
    public record Series(
            @Schema(description = "Currency for money series; null for count series",
                    example = "RON", nullable = true) String currency,
            @Schema(description = "Always true for money series (Stripe Test Mode); null for count series",
                    example = "true", nullable = true) Boolean testMode,
            @Schema(description = "Inclusive daily points, zero-filled, at most 366")
            List<AnalyticsDailyPointResponse> points) {

        public static Series countSeries(List<AnalyticsDailyPointResponse> points) {
            return new Series(null, null, List.copyOf(points));
        }

        public static Series moneySeries(String currency, List<AnalyticsDailyPointResponse> points) {
            return new Series(currency, Boolean.TRUE, List.copyOf(points));
        }
    }
}
