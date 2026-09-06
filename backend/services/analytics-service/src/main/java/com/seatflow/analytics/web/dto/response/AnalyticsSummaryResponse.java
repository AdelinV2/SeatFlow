package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * KPI summary for {@code GET /api/admin/analytics/summary} (TASK-P14-004 §6.2).
 *
 * <p>Operational counts are currency-neutral (one session counted once even with RON + EUR
 * revenue rows); revenue is currency-grouped minor-unit money. Rates use the P14-003 cohort
 * definitions, not ad-hoc controller ratios.
 */
@Schema(description = "Admin analytics KPI summary with cohort rates (Stripe Test Mode demo data)")
public record AnalyticsSummaryResponse(
        @Schema(description = "Inclusive range start (UTC)") LocalDate from,
        @Schema(description = "Inclusive range end (UTC)") LocalDate to,
        @Schema(description = "Applied UUID filters") Filters filters,
        @Schema(description = "Currency-neutral reservation KPIs") Reservations reservations,
        @Schema(description = "Currency-neutral ticket KPIs") Tickets tickets,
        @Schema(description = "Payment KPIs with currency-grouped revenue") Payments payments,
        @Schema(description = "Cohort rates with inspectable numerators/denominators") Rates rates,
        @Schema(description = "Projection freshness metadata") ProjectionFreshnessResponse freshness) {

    @Schema(description = "Applied UUID filters; both IDs supplied but unmatched yields empty data, never an error")
    public record Filters(
            @Schema(description = "Optional event filter", nullable = true) UUID eventId,
            @Schema(description = "Optional session filter; may be supplied without eventId", nullable = true)
            UUID eventSessionId) {
    }

    @Schema(description = "Currency-neutral reservation KPIs")
    public record Reservations(
            @Schema(description = "Reservations created in range", example = "120") long created,
            @Schema(description = "Created cohort confirmed (incl. later-refunded)", example = "87") long confirmed,
            @Schema(description = "Created cohort canonically expired", example = "25") long expired,
            @Schema(description = "Completed refunds in range (full-refund scope mirrors refunded reservations)",
                    example = "4") long refunded) {
    }

    @Schema(description = "Currency-neutral ticket KPIs")
    public record Tickets(
            @Schema(description = "Tickets issued in range", example = "174") long issued,
            @Schema(description = "Tickets revoked in range", example = "8") long revoked,
            @Schema(description = "Tickets scanned in range", example = "103") long scanned) {
    }

    @Schema(description = "Payment KPIs with currency-grouped revenue")
    public record Payments(
            @Schema(description = "Payments succeeded in range", example = "87") long succeeded,
            @Schema(description = "Payments with failure evidence in range", example = "11") long withFailure,
            @Schema(description = "Completed refunds in range", example = "4") long refundsCompleted,
            @Schema(description = "Revenue grouped by currency; never a mixed-currency scalar")
            List<MoneyMetricResponse> revenueByCurrency) {
    }

    @Schema(description = "Cohort rates from P14-003 definitions; the refund rate's event scope "
            + "resolves through reservation facts, so payments on sessions without a projected "
            + "session fact are still included")
    public record Rates(
            @Schema(description = "Confirmed-or-refunded over created cohort") RateMetricResponse reservationToPayment,
            @Schema(description = "Canonically expired over created cohort") RateMetricResponse expiration,
            @Schema(description = "Completed refunds over succeeded-payment cohort") RateMetricResponse refund) {
    }
}
