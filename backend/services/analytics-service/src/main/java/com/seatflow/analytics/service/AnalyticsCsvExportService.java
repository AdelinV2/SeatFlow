package com.seatflow.analytics.service;

import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;

import java.util.UUID;

/**
 * Bounded server-side CSV export over the {@code seatflow_analytics} read model
 * (TASK-P14-006 §6.8–§6.10).
 *
 * <p>The export is a long-form union of two aggregate grains: {@code OPERATIONS} rows carry
 * currency-neutral counts (one row per {@code daily_operational_metrics} grain) and
 * {@code REVENUE} rows carry currency-specific money (one row per
 * {@code daily_revenue_metrics} grain), so operational counts are never duplicated per
 * currency. Results come solely from analytics-owned facts/aggregates — no source-service
 * fanout, no PII.
 */
public interface AnalyticsCsvExportService {

    /**
     * Build the full CSV document as UTF-8 bytes.
     *
     * @param range validated inclusive UTC range (same P14-004 semantics)
     * @param eventId optional event scope
     * @param eventSessionId optional session scope
     * @return complete CSV document; never a partial file
     * @throws com.seatflow.common.domain.exception.ValidationException with
     *         {@code ANALYTICS_EXPORT_TOO_LARGE} when the union exceeds the row bound
     */
    byte[] exportDailyCsv(AnalyticsDateRange range, UUID eventId, UUID eventSessionId);
}
