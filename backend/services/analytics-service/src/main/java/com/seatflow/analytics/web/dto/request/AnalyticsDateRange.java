package com.seatflow.analytics.web.dto.request;

import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Validated inclusive UTC calendar-day range shared by all admin analytics queries.
 *
 * <p>Rules (TASK-P14-004 §6.1):
 * <ul>
 *   <li>both dates omitted → {@code [utcToday - 29d, utcToday]} (30 days) via injected Clock;</li>
 *   <li>exactly one boundary → {@code 400 INVALID_ANALYTICS_DATE_RANGE};</li>
 *   <li>{@code from > to} → {@code 400 INVALID_ANALYTICS_DATE_RANGE};</li>
 *   <li>inclusive span &gt; 366 days → {@code 400 ANALYTICS_DATE_RANGE_TOO_LARGE}
 *       (exactly 366 accepted, 367 rejected before any DB query).</li>
 * </ul>
 */
@Schema(description = "Validated inclusive UTC calendar-day range for admin analytics queries")
public record AnalyticsDateRange(
        @Schema(description = "Inclusive start date (UTC)", example = "2026-08-08")
        LocalDate from,
        @Schema(description = "Inclusive end date (UTC)", example = "2026-09-06")
        LocalDate to) {

    /** Maximum accepted inclusive span in calendar days. */
    public static final long MAX_SPAN_DAYS = 366L;

    /** Default window length when both boundaries are omitted (30 UTC days including today). */
    public static final long DEFAULT_WINDOW_DAYS = 30L;

    public AnalyticsDateRange {
        if (from == null || to == null) {
            throw new ValidationException(
                    "Analytics date range requires both from and to", ErrorCode.INVALID_ANALYTICS_DATE_RANGE);
        }
        if (from.isAfter(to)) {
            throw new ValidationException(
                    "Analytics date range is invalid: from must be on or before to",
                    ErrorCode.INVALID_ANALYTICS_DATE_RANGE);
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span > MAX_SPAN_DAYS) {
            throw new ValidationException(
                    "Analytics date range spans " + span + " days, maximum is " + MAX_SPAN_DAYS,
                    ErrorCode.ANALYTICS_DATE_RANGE_TOO_LARGE);
        }
    }

    /**
     * Resolve raw query parameters into a validated range.
     *
     * @param from optional inclusive start date
     * @param to optional inclusive end date
     * @param clock injected UTC clock for deterministic defaults
     */
    public static AnalyticsDateRange resolve(LocalDate from, LocalDate to, Clock clock) {
        if (from == null && to == null) {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            return new AnalyticsDateRange(today.minusDays(DEFAULT_WINDOW_DAYS - 1), today);
        }
        if (from == null || to == null) {
            throw new ValidationException(
                    "Analytics date range requires both from and to together",
                    ErrorCode.INVALID_ANALYTICS_DATE_RANGE);
        }
        return new AnalyticsDateRange(from, to);
    }
}
