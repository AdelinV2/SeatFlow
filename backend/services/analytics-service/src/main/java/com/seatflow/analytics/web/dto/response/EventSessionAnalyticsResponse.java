package com.seatflow.analytics.web.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One paginated event-session row for {@code GET /api/admin/analytics/sessions} and the
 * session detail endpoint (TASK-P14-004 §6.4–§6.5).
 *
 * <p>Operational counts are currency-neutral; revenue is grouped per currency. Missing
 * titles/labels are {@code null} (never live-fetched from Event Service). No PII is exposed.
 */
@Schema(description = "Event-session analytics row: currency-neutral counts plus per-currency revenue")
public record EventSessionAnalyticsResponse(
        @Schema(description = "Owning event ID") UUID eventId,
        @Schema(description = "Event session ID") UUID eventSessionId,
        @Schema(description = "Event title snapshot; null until a trusted lifecycle event provides it",
                nullable = true) String eventTitle,
        @Schema(description = "Session label snapshot; null until a trusted lifecycle event provides it",
                nullable = true) String sessionLabel,
        @Schema(description = "Session start time; null when unknown", nullable = true) Instant startsAt,
        @Schema(description = "Session status snapshot; null when unknown", nullable = true) String status,
        @Schema(description = "Trusted capacity snapshot; null when unavailable", nullable = true)
        Integer capacitySnapshot,
        @Schema(description = "Reservations created") long reservationsCreated,
        @Schema(description = "Reservations confirmed (incl. later-refunded)") long reservationsConfirmed,
        @Schema(description = "Reservations canonically expired") long reservationsExpired,
        @Schema(description = "Payments succeeded") long paymentsSucceeded,
        @Schema(description = "Payments with failure evidence") long paymentsWithFailure,
        @Schema(description = "Completed refunds") long refundsCompleted,
        @Schema(description = "Tickets issued") long ticketsIssued,
        @Schema(description = "Tickets revoked") long ticketsRevoked,
        @Schema(description = "Tickets scanned") long ticketsScanned,
        @Schema(description = "Revenue grouped by currency; never a mixed-currency scalar")
        List<MoneyMetricResponse> revenueByCurrency,
        @Schema(description = "Active issued tickets over trusted capacity; null when capacity unavailable",
                nullable = true) BigDecimal occupancyRatio,
        @Schema(description = "Scanned over eligible issued tickets; null when no eligible tickets",
                nullable = true) BigDecimal attendanceRatio,
        @Schema(description = "Max projected source-event time for this session; null before the first event",
                nullable = true) Instant lastProjectedEventAt) {
}
