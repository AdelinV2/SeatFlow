package com.seatflow.analytics.model.enums;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;

/**
 * Allowlisted sort fields for {@code GET /api/admin/analytics/sessions}.
 *
 * <p>Parsing is strict: unknown fields or directions fail with
 * {@code INVALID_ANALYTICS_SORT} so sort injection is impossible by construction.
 */
public enum AnalyticsSessionSort {
    STARTS_AT("startsAt"),
    GROSS_REVENUE("grossRevenue"),
    TICKETS_ISSUED("ticketsIssued"),
    TICKETS_SCANNED("ticketsScanned"),
    RESERVATIONS_CREATED("reservationsCreated");

    private final String param;

    AnalyticsSessionSort(String param) {
        this.param = param;
    }

    public String param() {
        return param;
    }

    public boolean isMonetary() {
        return this == GROSS_REVENUE;
    }

    public static AnalyticsSessionSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return STARTS_AT;
        }
        String normalized = raw.trim();
        for (AnalyticsSessionSort sort : values()) {
            if (sort.param.equals(normalized)) {
                return sort;
            }
        }
        throw new ValidationException(
                "Unsupported analytics session sort: " + raw
                        + ". Allowed: startsAt, grossRevenue, ticketsIssued, ticketsScanned, reservationsCreated",
                ErrorCode.INVALID_ANALYTICS_SORT);
    }
}
