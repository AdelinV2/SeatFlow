package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Bounded filter-options envelope for TASK-P14-006 §6.1.
 *
 * <p>At most {@code 500} items are ever returned. When more values match, the first
 * {@code 500} (in the endpoint's deterministic order) are returned with
 * {@code truncated=true} so the dashboard can warn instead of silently hiding options.
 * {@code totalProjected} is the exact matching count from a bounded count query.
 */
@Schema(description = "Bounded filter-options envelope with explicit truncation metadata")
public record AnalyticsFilterOptionsResponse<T>(
        @Schema(description = "At most 500 options in the endpoint's deterministic order")
        List<T> items,
        @Schema(description = "Exact number of matching projected values", example = "12")
        long totalProjected,
        @Schema(description = "True when more than 500 values match and the list was cut",
                example = "false")
        boolean truncated) {

    public static <T> AnalyticsFilterOptionsResponse<T> of(
            List<T> items, long totalProjected, boolean truncated) {
        return new AnalyticsFilterOptionsResponse<>(List.copyOf(items), totalProjected, truncated);
    }

    public static <T> AnalyticsFilterOptionsResponse<T> empty() {
        return new AnalyticsFilterOptionsResponse<>(List.of(), 0L, false);
    }
}
