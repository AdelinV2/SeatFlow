package com.seatflow.analytics.web.controller;

import com.seatflow.analytics.model.enums.AnalyticsSessionSort;
import com.seatflow.analytics.model.enums.AnalyticsTimeseriesMetric;
import com.seatflow.analytics.model.enums.AnalyticsTopMetric;
import com.seatflow.analytics.service.AdminAnalyticsQueryService;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.analytics.web.dto.response.AnalyticsSummaryResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsTimeSeriesResponse;
import com.seatflow.analytics.web.dto.response.EventSessionAnalyticsResponse;
import com.seatflow.analytics.web.dto.response.TopAnalyticsItemResponse;
import com.seatflow.common.domain.dto.ApiErrorResponse;
import com.seatflow.common.domain.dto.PagedResult;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only analytics REST contracts (TASK-P14-004).
 *
 * <p>Pure HTTP adapter: parses/validates query parameters into {@link AnalyticsDateRange} and
 * allowlisted enums, then delegates to {@link AdminAnalyticsQueryService}. All endpoints require
 * {@code ROLE_ADMIN} (enforced by {@code SecurityConfig} on {@code /api/admin/analytics/**}).
 * Results come solely from the {@code seatflow_analytics} read model — Stripe Test Mode demo
 * data, eventually consistent, no PII.
 *
 * <p>Phase 14 dashboard contracts recorded for P14-005 (review REV-004/005/006):
 * <ul>
 *   <li>listing rows are <em>in-range</em> sums while session detail is <em>lifetime</em> —
 *       the two views must be labeled accordingly, never compared as equals;</li>
 *   <li>rows listed with null title/label (no projected session fact yet) return 404 on the
 *       detail endpoint — the dashboard must handle that gracefully;</li>
 *   <li>the freshness envelope is exposed on summary only; timeseries/sessions/top carry no
 *       recency marker, so the dashboard must show summary freshness alongside them.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@Tag(name = "Admin Analytics",
        description = "ADMIN-only analytics read-model APIs (Stripe Test Mode demo data, eventually consistent)")
public class AdminAnalyticsController {

    private final AdminAnalyticsQueryService queryService;
    private final Clock analyticsClock;

    @GetMapping("/summary")
    @Operation(summary = "Get analytics KPI summary",
            description = "Currency-neutral KPI cards plus P14-003 cohort rates over an inclusive UTC date range.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Summary retrieved",
                content = @Content(schema = @Schema(implementation = AnalyticsSummaryResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid date range",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "ADMIN role required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AnalyticsSummaryResponse> getSummary(
            @Parameter(description = "Inclusive start date (YYYY-MM-DD, UTC)", example = "2026-08-08")
            @RequestParam(required = false) String from,
            @Parameter(description = "Inclusive end date (YYYY-MM-DD, UTC)", example = "2026-09-06")
            @RequestParam(required = false) String to,
            @Parameter(description = "Optional event filter")
            @RequestParam(required = false) UUID eventId,
            @Parameter(description = "Optional session filter; may be supplied without eventId")
            @RequestParam(required = false) UUID eventSessionId) {
        AnalyticsDateRange range = resolveRange(from, to);
        return ResponseEntity.ok(queryService.getSummary(range, eventId, eventSessionId));
    }

    @GetMapping("/timeseries")
    @Operation(summary = "Get daily analytics time series",
            description = "DAY granularity only. Money metrics return one series per currency; "
                    + "count metrics return a single currency-neutral series. Missing dates are zero-filled.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Time series retrieved",
                content = @Content(schema = @Schema(implementation = AnalyticsTimeSeriesResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid date range or metric",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "ADMIN role required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AnalyticsTimeSeriesResponse> getTimeSeries(
            @Parameter(description = "Inclusive start date (YYYY-MM-DD, UTC)")
            @RequestParam(required = false) String from,
            @Parameter(description = "Inclusive end date (YYYY-MM-DD, UTC)")
            @RequestParam(required = false) String to,
            @Parameter(description = "Optional event filter")
            @RequestParam(required = false) UUID eventId,
            @Parameter(description = "Optional session filter; may be supplied without eventId")
            @RequestParam(required = false) UUID eventSessionId,
            @Parameter(description = "Metric: GROSS_REVENUE | NET_REVENUE | TICKETS_ISSUED | "
                    + "TICKETS_SCANNED | RESERVATIONS_CREATED | PAYMENTS_SUCCEEDED",
                    example = "GROSS_REVENUE", required = true)
            @RequestParam(required = false) String metric) {
        AnalyticsDateRange range = resolveRange(from, to);
        return ResponseEntity.ok(queryService.getTimeSeries(
                range, eventId, eventSessionId, AnalyticsTimeseriesMetric.parse(metric)));
    }

    @GetMapping("/sessions")
    @Operation(summary = "List event-session analytics",
            description = "Paginated in-range sessions (rows are range-bound sums, unlike the "
                    + "lifetime session detail — label accordingly). Operational rows are paged first, then enriched "
                    + "with one batched revenue query. sort=grossRevenue orders by true in-range gross "
                    + "(refunds do not reduce it) and requires currency=<3-letter code>; "
                    + "for other sorts currency is ignored. Sessions without revenue in the requested "
                    + "currency sort as zero under grossRevenue ordering.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Session page retrieved",
                content = @Content(schema = @Schema(implementation = PagedResult.class))),
        @ApiResponse(responseCode = "400", description = "Invalid range, pagination, sort, or currency",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "ADMIN role required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<PagedResult<EventSessionAnalyticsResponse>> getSessions(
            @Parameter(description = "Inclusive start date (YYYY-MM-DD, UTC)")
            @RequestParam(required = false) String from,
            @Parameter(description = "Inclusive end date (YYYY-MM-DD, UTC)")
            @RequestParam(required = false) String to,
            @Parameter(description = "Optional event filter")
            @RequestParam(required = false) UUID eventId,
            @Parameter(description = "Zero-based page index", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size 1..100", example = "25")
            @RequestParam(defaultValue = "25") int size,
            @Parameter(description = "Sort as field,direction. Allowed fields: startsAt, grossRevenue, "
                    + "ticketsIssued, ticketsScanned, reservationsCreated",
                    example = "startsAt,desc")
            @RequestParam(defaultValue = "startsAt,desc") String sort,
            @Parameter(description = "Required only for sort=grossRevenue; ignored otherwise",
                    example = "RON")
            @RequestParam(required = false) String currency) {
        AnalyticsDateRange range = resolveRange(from, to);
        SortRequest sortRequest = parseSort(sort);
        return ResponseEntity.ok(queryService.getSessions(
                range, eventId, page, size, sortRequest.field(), sortRequest.descending(), currency));
    }

    @GetMapping("/sessions/{eventSessionId}")
    @Operation(summary = "Get one projected session detail",
            description = "Lifetime operational counts plus per-currency revenue (not range-bound; "
                    + "values may differ from in-range listing rows by construction). 404 only when analytics "
                    + "holds no session fact for the ID — including rows the listing shows with null title — "
                    + "so the dashboard must handle 404 gracefully; no source-service lookup is performed.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Session detail retrieved",
                content = @Content(schema = @Schema(implementation = EventSessionAnalyticsResponse.class))),
        @ApiResponse(responseCode = "404", description = "No analytics projection for this session",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "ADMIN role required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<EventSessionAnalyticsResponse> getSessionDetail(
            @Parameter(description = "Event session ID") @PathVariable UUID eventSessionId) {
        return ResponseEntity.ok(queryService.getSessionDetail(eventSessionId));
    }

    @GetMapping("/top")
    @Operation(summary = "Get top event sessions",
            description = "Deterministic ranking (metric DESC, eventSessionId ASC). NET_REVENUE requires "
                    + "currency and ranks only that currency by net (gross minus refunded); count rankings are currency-neutral.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ranking retrieved",
                content = @Content(schema = @Schema(implementation = TopAnalyticsItemResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid range, metric, limit, or missing currency",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "ADMIN role required",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<List<TopAnalyticsItemResponse>> getTop(
            @Parameter(description = "Inclusive start date (YYYY-MM-DD, UTC)")
            @RequestParam(required = false) String from,
            @Parameter(description = "Inclusive end date (YYYY-MM-DD, UTC)")
            @RequestParam(required = false) String to,
            @Parameter(description = "Optional event filter")
            @RequestParam(required = false) UUID eventId,
            @Parameter(description = "Metric: NET_REVENUE | TICKETS_ISSUED | TICKETS_SCANNED | "
                    + "RESERVATIONS_CONFIRMED", example = "NET_REVENUE", required = true)
            @RequestParam(required = false) String metric,
            @Parameter(description = "Limit 1..20", example = "5")
            @RequestParam(defaultValue = "5") int limit,
            @Parameter(description = "Required only for NET_REVENUE", example = "RON")
            @RequestParam(required = false) String currency) {
        AnalyticsDateRange range = resolveRange(from, to);
        return ResponseEntity.ok(queryService.getTop(
                range, eventId, AnalyticsTopMetric.parse(metric), limit, currency));
    }

    private AnalyticsDateRange resolveRange(String from, String to) {
        return AnalyticsDateRange.resolve(parseDate(from, "from"), parseDate(to, "to"), analyticsClock);
    }

    private static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new ValidationException(
                    "Analytics date '" + field + "' must use YYYY-MM-DD format: " + raw,
                    ErrorCode.INVALID_ANALYTICS_DATE_RANGE);
        }
    }

    private record SortRequest(AnalyticsSessionSort field, boolean descending) {
    }

    private static SortRequest parseSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return new SortRequest(AnalyticsSessionSort.STARTS_AT, true);
        }
        String[] parts = raw.split(",", -1);
        AnalyticsSessionSort field = AnalyticsSessionSort.parse(parts[0]);
        boolean descending = true;
        if (parts.length > 1) {
            descending = parseDirection(parts[1]);
        }
        if (parts.length > 2) {
            throw new ValidationException(
                    "Analytics sort must use field,direction format: " + raw,
                    ErrorCode.INVALID_ANALYTICS_SORT);
        }
        return new SortRequest(field, descending);
    }

    private static boolean parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "desc" -> true;
            case "asc" -> false;
            default -> throw new ValidationException(
                    "Analytics sort direction must be asc or desc: " + raw,
                    ErrorCode.INVALID_ANALYTICS_SORT);
        };
    }
}
