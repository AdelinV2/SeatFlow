package com.seatflow.analytics.web.csv;

import java.util.List;

/**
 * Deterministic UTF-8 / RFC-4180-style CSV writer for TASK-P14-006 §6.8–§6.10.
 *
 * <p>Stable column contract (in this exact order):
 * <pre>
 * row_type, metric_date, event_id, event_session_id, event_title, session_label,
 * currency, reservations_created, reservations_confirmed, reservations_expired,
 * operational_payments_succeeded, payments_with_failure, operational_refunds_completed,
 * tickets_issued, tickets_revoked, tickets_scanned, financial_payments_succeeded,
 * financial_refunds_completed, gross_revenue_minor, refunded_revenue_minor,
 * net_revenue_minor, stripe_test_mode, last_projected_event_at
 * </pre>
 *
 * <p>Escaping rules:
 * <ul>
 *   <li>{@code null} renders as an empty cell;</li>
 *   <li>user-controllable text snapshots ({@code event_title}, {@code session_label}, and any
 *       future user text) go through {@link #textCell}: when the first non-whitespace
 *       character is {@code =}, {@code +}, {@code -}, or {@code @}, the value is prefixed
 *       with a single quote so spreadsheets never execute it as a formula;</li>
 *   <li>typed UUID/date/numeric/currency values are emitted from typed server values via
 *       {@link #plainCell} (no formula guard — they cannot be attacker text);</li>
 *   <li>{@code "} is escaped as {@code ""} and any field containing a comma, quote, CR,
 *       or LF is wrapped in quotes; raw CR/LF is never emitted unquoted.</li>
 * </ul>
 *
 * <p>Rows are terminated with {@code \n}. No PII is ever written: the export carries only
 * aggregate counts, minor-unit money, currency codes, and analytics-owned title snapshots.
 */
public final class AnalyticsCsvWriter {

    /** Exact stable header in export order (TASK-P14-006 §6.8). */
    public static final List<String> HEADER = List.of(
            "row_type",
            "metric_date",
            "event_id",
            "event_session_id",
            "event_title",
            "session_label",
            "currency",
            "reservations_created",
            "reservations_confirmed",
            "reservations_expired",
            "operational_payments_succeeded",
            "payments_with_failure",
            "operational_refunds_completed",
            "tickets_issued",
            "tickets_revoked",
            "tickets_scanned",
            "financial_payments_succeeded",
            "financial_refunds_completed",
            "gross_revenue_minor",
            "refunded_revenue_minor",
            "net_revenue_minor",
            "stripe_test_mode",
            "last_projected_event_at");

    private AnalyticsCsvWriter() {
    }

    /** Header line (never quoted: no header name contains a special character). */
    public static String headerLine() {
        return String.join(",", HEADER);
    }

    /**
     * Escape a user-controllable text snapshot cell with spreadsheet-formula protection.
     *
     * @param value raw snapshot text, may be {@code null}
     * @return escaped, quoted-when-needed cell
     */
    public static String textCell(String value) {
        if (value == null) {
            return "";
        }
        return quote(guardFormula(value));
    }

    /**
     * Escape a typed server value (UUID, date, instant, number, currency code, enum name).
     *
     * @param value pre-formatted typed value, may be {@code null}
     * @return escaped, quoted-when-needed cell
     */
    public static String plainCell(String value) {
        if (value == null) {
            return "";
        }
        return quote(value);
    }

    /** Join already-escaped cells into one row (no line terminator). */
    public static String row(String... cells) {
        return String.join(",", cells);
    }

    /** Join already-escaped cells into one row (no line terminator). */
    public static String row(List<String> cells) {
        return String.join(",", cells);
    }

    private static String guardFormula(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c == '=' || c == '+' || c == '-' || c == '@') {
                return "'" + value;
            }
            return value;
        }
        return value;
    }

    private static String quote(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
