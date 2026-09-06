package com.seatflow.analytics.service.impl;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.entity.EventSessionMetric;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.service.AnalyticsCsvExportService;
import com.seatflow.analytics.web.csv.AnalyticsCsvWriter;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side bounded CSV export (TASK-P14-006 §6.8–§6.10).
 *
 * <p>Bound protocol: the union size is counted first for a fast-path rejection,
 * then both grains are loaded through capped queries under one
 * {@code REPEATABLE_READ} snapshot and the actually loaded union is re-checked
 * before any response bytes are built ({@code 10_000} accepted, {@code 10_001+}
 * fails with {@code ANALYTICS_EXPORT_TOO_LARGE}), so no partial file is ever produced. Grain rule: operational counts appear only on
 * {@code OPERATIONS} rows, money only on {@code REVENUE} rows — one session/date with
 * RON + EUR activity yields one {@code OPERATIONS} row and two {@code REVENUE} rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AnalyticsCsvExportServiceImpl implements AnalyticsCsvExportService {

    /** Maximum total exported rows across both row types. */
    public static final int MAX_EXPORT_ROWS = 10_000;

    private final AnalyticsAdminQueryRepository adminQueries;
    private final AnalyticsSessionFactRepository sessionFactRepository;
    private final EventSessionMetricRepository sessionMetricRepository;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] exportDailyCsv(AnalyticsDateRange range, UUID eventId, UUID eventSessionId) {
        log.info("Admin analytics CSV export requested. from={}, to={}, eventId={}, eventSessionId={}",
                range.from(), range.to(), eventId, eventSessionId);

        // Fast-path preflight: reject a clearly oversized export before loading
        // any rows, so no partial file is ever produced.
        long operationalCount =
                adminQueries.countOperationalRows(range.from(), range.to(), eventId, eventSessionId);
        long revenueCount =
                adminQueries.countRevenueRows(range.from(), range.to(), eventId, eventSessionId);
        long total = operationalCount + revenueCount;
        if (total > MAX_EXPORT_ROWS) {
            throw tooLarge(total);
        }

        // REV-001: the preflight above is not the authority. Counts and loads
        // share one REPEATABLE_READ snapshot, and both loads are capped; the
        // actually loaded union is re-checked before any response bytes are
        // built, so a concurrent projection commit in the window can only cause
        // a clean ANALYTICS_EXPORT_TOO_LARGE rejection — never an over-limit
        // file. Exactly MAX_EXPORT_ROWS still succeeds.
        List<DailyOperationalMetric> operational = adminQueries.findOperationalRowsCapped(
                range.from(), range.to(), eventId, eventSessionId, MAX_EXPORT_ROWS + 1);
        if (operational.size() > MAX_EXPORT_ROWS) {
            throw tooLarge(operational.size());
        }
        List<DailyRevenueMetric> revenue = adminQueries.findRevenueRowsCapped(
                range.from(), range.to(), eventId, eventSessionId,
                MAX_EXPORT_ROWS - operational.size() + 1);
        if (operational.size() + revenue.size() > MAX_EXPORT_ROWS) {
            throw tooLarge((long) operational.size() + revenue.size());
        }

        DisplaySnapshots snapshots = loadSnapshots(operational, revenue);
        List<CsvRow> rows = new ArrayList<>(operational.size() + revenue.size());
        for (DailyOperationalMetric metric : operational) {
            rows.add(CsvRow.operations(metric, snapshots));
        }
        for (DailyRevenueMetric metric : revenue) {
            rows.add(CsvRow.revenue(metric, snapshots));
        }
        rows.sort(CsvRow.ORDER);

        List<String> lines = new ArrayList<>(rows.size() + 1);
        lines.add(AnalyticsCsvWriter.headerLine());
        for (CsvRow row : rows) {
            lines.add(row.toLine());
        }
        String document = String.join("\n", lines) + "\n";
        log.info("Admin analytics CSV export built. rows={}, from={}, to={}",
                rows.size(), range.from(), range.to());
        return document.getBytes(StandardCharsets.UTF_8);
    }

    private static ValidationException tooLarge(long total) {
        return new ValidationException(
                "Analytics export matches " + total + " rows, maximum is " + MAX_EXPORT_ROWS
                        + ". Narrow the date range or filters and retry.",
                ErrorCode.ANALYTICS_EXPORT_TOO_LARGE);
    }

    private DisplaySnapshots loadSnapshots(
            List<DailyOperationalMetric> operational, List<DailyRevenueMetric> revenue) {
        Set<UUID> sessionIds = new HashSet<>();
        for (DailyOperationalMetric metric : operational) {
            sessionIds.add(metric.getEventSessionId());
        }
        for (DailyRevenueMetric metric : revenue) {
            sessionIds.add(metric.getEventSessionId());
        }
        Map<UUID, AnalyticsSessionFact> facts = new HashMap<>();
        Map<UUID, EventSessionMetric> metrics = new HashMap<>();
        if (!sessionIds.isEmpty()) {
            sessionFactRepository.findAllById(sessionIds)
                    .forEach(fact -> facts.put(fact.getEventSessionId(), fact));
            sessionMetricRepository.findAllById(sessionIds)
                    .forEach(metric -> metrics.put(metric.getEventSessionId(), metric));
        }
        return new DisplaySnapshots(facts, metrics);
    }

    /** Analytics-owned display snapshots for one session (titles/labels/recency only). */
    private record DisplaySnapshots(
            Map<UUID, AnalyticsSessionFact> facts,
            Map<UUID, EventSessionMetric> metrics) {

        String eventTitle(UUID sessionId) {
            AnalyticsSessionFact fact = facts.get(sessionId);
            return fact != null ? fact.getEventTitle() : null;
        }

        String sessionLabel(UUID sessionId) {
            AnalyticsSessionFact fact = facts.get(sessionId);
            return fact != null ? fact.getSessionLabel() : null;
        }

        String lastProjectedEventAt(UUID sessionId) {
            EventSessionMetric metric = metrics.get(sessionId);
            if (metric != null && metric.getLastProjectedEventAt() != null) {
                return metric.getLastProjectedEventAt().toString();
            }
            AnalyticsSessionFact fact = facts.get(sessionId);
            if (fact != null && fact.getLastSourceEventAt() != null) {
                return fact.getLastSourceEventAt().toString();
            }
            return null;
        }
    }

    /**
     * One long-form export row. Operational counts and financial money are mutually
     * exclusive by construction: each factory populates only its own grain's columns.
     */
    private record CsvRow(
            String rowType,
            LocalDate metricDate,
            UUID eventId,
            UUID eventSessionId,
            String eventTitle,
            String sessionLabel,
            String currency,
            Long reservationsCreated,
            Long reservationsConfirmed,
            Long reservationsExpired,
            Long operationalPaymentsSucceeded,
            Long paymentsWithFailure,
            Long operationalRefundsCompleted,
            Long ticketsIssued,
            Long ticketsRevoked,
            Long ticketsScanned,
            Long financialPaymentsSucceeded,
            Long financialRefundsCompleted,
            Long grossRevenueMinor,
            Long refundedRevenueMinor,
            Long netRevenueMinor,
            String stripeTestMode,
            String lastProjectedEventAt) {

        /** Stable export order: date, event, session, OPERATIONS before REVENUE, currency. */
        static final Comparator<CsvRow> ORDER = Comparator
                .comparing(CsvRow::metricDate)
                .thenComparing(CsvRow::eventId)
                .thenComparing(CsvRow::eventSessionId)
                .thenComparing(row -> "OPERATIONS".equals(row.rowType()) ? 0 : 1)
                .thenComparing(CsvRow::currency, Comparator.nullsFirst(String::compareTo));

        static CsvRow operations(DailyOperationalMetric metric, DisplaySnapshots snapshots) {
            return new CsvRow(
                    "OPERATIONS",
                    metric.getMetricDate(), metric.getEventId(), metric.getEventSessionId(),
                    snapshots.eventTitle(metric.getEventSessionId()),
                    snapshots.sessionLabel(metric.getEventSessionId()),
                    null,
                    metric.getReservationsCreated(), metric.getReservationsConfirmed(),
                    metric.getReservationsExpired(), metric.getPaymentsSucceeded(),
                    metric.getPaymentsWithFailure(), metric.getRefundsCompleted(),
                    metric.getTicketsIssued(), metric.getTicketsRevoked(), metric.getTicketsScanned(),
                    null, null, null, null, null,
                    null,
                    snapshots.lastProjectedEventAt(metric.getEventSessionId()));
        }

        static CsvRow revenue(DailyRevenueMetric metric, DisplaySnapshots snapshots) {
            return new CsvRow(
                    "REVENUE",
                    metric.getMetricDate(), metric.getEventId(), metric.getEventSessionId(),
                    snapshots.eventTitle(metric.getEventSessionId()),
                    snapshots.sessionLabel(metric.getEventSessionId()),
                    metric.getCurrency(),
                    null, null, null, null, null, null, null, null, null,
                    metric.getPaymentsSucceeded(), metric.getRefundsCompleted(),
                    metric.getGrossRevenueMinor(), metric.getRefundedRevenueMinor(),
                    metric.getGrossRevenueMinor() - metric.getRefundedRevenueMinor(),
                    "true",
                    snapshots.lastProjectedEventAt(metric.getEventSessionId()));
        }

        String toLine() {
            return AnalyticsCsvWriter.row(
                    AnalyticsCsvWriter.plainCell(rowType),
                    AnalyticsCsvWriter.plainCell(metricDate.toString()),
                    AnalyticsCsvWriter.plainCell(eventId.toString()),
                    AnalyticsCsvWriter.plainCell(eventSessionId.toString()),
                    AnalyticsCsvWriter.textCell(eventTitle),
                    AnalyticsCsvWriter.textCell(sessionLabel),
                    AnalyticsCsvWriter.plainCell(currency),
                    number(reservationsCreated),
                    number(reservationsConfirmed),
                    number(reservationsExpired),
                    number(operationalPaymentsSucceeded),
                    number(paymentsWithFailure),
                    number(operationalRefundsCompleted),
                    number(ticketsIssued),
                    number(ticketsRevoked),
                    number(ticketsScanned),
                    number(financialPaymentsSucceeded),
                    number(financialRefundsCompleted),
                    number(grossRevenueMinor),
                    number(refundedRevenueMinor),
                    number(netRevenueMinor),
                    AnalyticsCsvWriter.plainCell(stripeTestMode),
                    AnalyticsCsvWriter.plainCell(lastProjectedEventAt));
        }

        private static String number(Long value) {
            return value == null ? "" : Long.toString(value);
        }
    }
}
