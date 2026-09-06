package com.seatflow.analytics.model.enums;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;

/**
 * Ranking metrics for {@code GET /api/admin/analytics/top}.
 *
 * <p>Count rankings are currency-neutral; {@code NET_REVENUE} ranks exactly one requested
 * currency and never compares mixed-currency money.
 */
public enum AnalyticsTopMetric {
    NET_REVENUE,
    TICKETS_ISSUED,
    TICKETS_SCANNED,
    RESERVATIONS_CONFIRMED;

    public boolean isMoney() {
        return this == NET_REVENUE;
    }

    public static AnalyticsTopMetric parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(
                    "Unsupported analytics top metric: " + raw, ErrorCode.INVALID_ANALYTICS_SORT);
        }
    }
}
