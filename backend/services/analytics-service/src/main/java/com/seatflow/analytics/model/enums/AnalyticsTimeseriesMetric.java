package com.seatflow.analytics.model.enums;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;

/**
 * Count vs financial time-series metrics for {@code GET /api/admin/analytics/timeseries}.
 *
 * <p>Money metrics read {@code daily_revenue_metrics} (one series per currency);
 * count metrics read {@code daily_operational_metrics} (one currency-neutral series).
 */
public enum AnalyticsTimeseriesMetric {
    GROSS_REVENUE,
    NET_REVENUE,
    TICKETS_ISSUED,
    TICKETS_SCANNED,
    RESERVATIONS_CREATED,
    PAYMENTS_SUCCEEDED;

    public boolean isMoney() {
        return this == GROSS_REVENUE || this == NET_REVENUE;
    }

    public static AnalyticsTimeseriesMetric parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(
                    "Unsupported analytics timeseries metric: " + raw, ErrorCode.INVALID_ANALYTICS_SORT);
        }
    }
}
