package com.seatflow.analytics.service;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.entity.EventSessionMetric;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.service.impl.AnalyticsCsvExportServiceImpl;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-P14-006 §6.8–§6.10: CSV grain separation, deterministic ordering, formula
 * protection, and the 10_000-row bound (no partial file on overflow).
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsCsvExportServiceImplTest {

    static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID S1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final LocalDate SEP_06 = LocalDate.of(2026, 9, 6);
    static final Instant PROJECTED_AT = Instant.parse("2026-09-06T11:30:00Z");
    static final Instant SOURCE_AT = Instant.parse("2026-09-06T10:00:00Z");

    @Mock
    private AnalyticsAdminQueryRepository adminQueries;

    @Mock
    private AnalyticsSessionFactRepository sessionFactRepository;

    @Mock
    private EventSessionMetricRepository sessionMetricRepository;

    @InjectMocks
    private AnalyticsCsvExportServiceImpl exportService;

    private static AnalyticsDateRange range() {
        return new AnalyticsDateRange(LocalDate.of(2026, 9, 5), SEP_06);
    }

    @Test
    void shouldKeepOperationalCountsOutOfRevenueRowsAndOrderDeterministically() {
        when(adminQueries.countOperationalRows(any(), any(), any(), any())).thenReturn(1L);
        when(adminQueries.countRevenueRows(any(), any(), any(), any())).thenReturn(2L);
        when(adminQueries.findOperationalRowsCapped(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(operationalRow()));
        // EUR listed before RON on purpose: output must still order currency ASC.
        when(adminQueries.findRevenueRowsCapped(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(revenueRow("EUR", 3, 0, 500_000L, 0L),
                        revenueRow("RON", 5, 1, 1_000_000L, 100_000L)));
        when(sessionFactRepository.findAllById(eq(Set.of(S1))))
                .thenReturn(List.of(sessionFact("Hamlet", "Evening")));
        when(sessionMetricRepository.findAllById(eq(Set.of(S1))))
                .thenReturn(List.of(EventSessionMetric.builder()
                        .eventSessionId(S1).eventId(EVENT_ID)
                        .lastProjectedEventAt(PROJECTED_AT)
                        .updatedAt(PROJECTED_AT).build()));

        String csv = new String(exportService.exportDailyCsv(range(), null, null), StandardCharsets.UTF_8);

        // Each expected line lists every one of the 23 stable columns explicitly, so a
        // missing/extra column fails loudly instead of hiding in comma counting.
        String header = line("row_type", "metric_date", "event_id", "event_session_id",
                "event_title", "session_label", "currency", "reservations_created",
                "reservations_confirmed", "reservations_expired", "operational_payments_succeeded",
                "payments_with_failure", "operational_refunds_completed", "tickets_issued",
                "tickets_revoked", "tickets_scanned", "financial_payments_succeeded",
                "financial_refunds_completed", "gross_revenue_minor", "refunded_revenue_minor",
                "net_revenue_minor", "stripe_test_mode", "last_projected_event_at");
        String operations = line("OPERATIONS", "2026-09-06", EVENT_ID.toString(), S1.toString(),
                "Hamlet", "Evening", "", "10", "8", "1", "8", "1", "1", "16", "2", "9",
                "", "", "", "", "", "", PROJECTED_AT.toString());
        String eur = line("REVENUE", "2026-09-06", EVENT_ID.toString(), S1.toString(),
                "Hamlet", "Evening", "EUR", "", "", "", "", "", "", "", "", "", "3", "0",
                "500000", "0", "500000", "true", PROJECTED_AT.toString());
        String ron = line("REVENUE", "2026-09-06", EVENT_ID.toString(), S1.toString(),
                "Hamlet", "Evening", "RON", "", "", "", "", "", "", "", "", "", "5", "1",
                "1000000", "100000", "900000", "true", PROJECTED_AT.toString());
        assertThat(csv).isEqualTo(header + "\n" + operations + "\n" + eur + "\n" + ron + "\n");
    }

    private static String line(String... cells) {
        return String.join(",", cells);
    }

    @Test
    void shouldGuardMaliciousSnapshotTextInExportRows() {
        when(adminQueries.countOperationalRows(any(), any(), any(), any())).thenReturn(1L);
        when(adminQueries.countRevenueRows(any(), any(), any(), any())).thenReturn(0L);
        when(adminQueries.findOperationalRowsCapped(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(operationalRow()));
        when(adminQueries.findRevenueRowsCapped(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(sessionFactRepository.findAllById(any()))
                .thenReturn(List.of(sessionFact("=HYPERLINK(\"https://example.invalid\")", "a,b")));
        when(sessionMetricRepository.findAllById(any())).thenReturn(List.of());

        String csv = new String(exportService.exportDailyCsv(range(), null, null), StandardCharsets.UTF_8);

        assertThat(csv).contains("\"'=HYPERLINK(\"\"https://example.invalid\"\")\",\"a,b\",,");
        // Fact fallback supplies recency when no lifetime metric exists.
        assertThat(csv).contains("," + SOURCE_AT + "\n");
    }

    @Test
    void shouldReject10001RowsWithStableErrorAndLoadNothing() {
        when(adminQueries.countOperationalRows(any(), any(), any(), any())).thenReturn(9_000L);
        when(adminQueries.countRevenueRows(any(), any(), any(), any())).thenReturn(1_001L);

        assertThatThrownBy(() -> exportService.exportDailyCsv(range(), null, null))
                .isInstanceOf(ValidationException.class)
                .extracting(ex -> ((ValidationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYTICS_EXPORT_TOO_LARGE);

        verify(adminQueries, never()).findOperationalRowsCapped(any(), any(), any(), any(), anyInt());
        verify(adminQueries, never()).findRevenueRowsCapped(any(), any(), any(), any(), anyInt());
        verify(sessionFactRepository, never()).findAllById(any());
    }

    @Test
    void shouldAcceptExactly10000LoadedRows() {
        // REV-001: the old test mocked a 10_000 count while loading zero rows,
        // proving nothing about the real boundary. Here the loads carry real
        // row objects: 9_999 operational + 1 revenue = exactly 10_000 union.
        when(adminQueries.countOperationalRows(any(), any(), any(), any())).thenReturn(9_999L);
        when(adminQueries.countRevenueRows(any(), any(), any(), any())).thenReturn(1L);
        List<DailyOperationalMetric> operational = new ArrayList<>();
        for (int i = 0; i < 9_999; i++) {
            operational.add(operationalRow());
        }
        when(adminQueries.findOperationalRowsCapped(any(), any(), any(), any(), anyInt()))
                .thenReturn(operational);
        when(adminQueries.findRevenueRowsCapped(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(revenueRow("RON", 1, 0, 1_000L, 0L)));

        byte[] csv = exportService.exportDailyCsv(range(), null, null);

        assertThat(new String(csv, StandardCharsets.UTF_8).lines().count()).isEqualTo(10_001L);
    }

    @Test
    void shouldRejectWhenLoadedUnionExceedsBoundDespiteStalePreflight() {
        // REV-001: simulates a projection commit landing between the preflight
        // counts and retrieval — the preflight observes exactly 10_000, but the
        // capped loads see 10_001. The post-load union check is authoritative.
        when(adminQueries.countOperationalRows(any(), any(), any(), any())).thenReturn(9_999L);
        when(adminQueries.countRevenueRows(any(), any(), any(), any())).thenReturn(1L);
        List<DailyOperationalMetric> operational = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            operational.add(operationalRow());
        }
        when(adminQueries.findOperationalRowsCapped(any(), any(), any(), any(), anyInt()))
                .thenReturn(operational);
        when(adminQueries.findRevenueRowsCapped(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(revenueRow("RON", 1, 0, 1_000L, 0L)));

        assertThatThrownBy(() -> exportService.exportDailyCsv(range(), null, null))
                .isInstanceOf(ValidationException.class)
                .extracting(ex -> ((ValidationException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ANALYTICS_EXPORT_TOO_LARGE);

        verify(sessionFactRepository, never()).findAllById(any());
    }

    private static DailyOperationalMetric operationalRow() {
        return DailyOperationalMetric.builder()
                .metricDate(SEP_06).eventId(EVENT_ID).eventSessionId(S1)
                .reservationsCreated(10).reservationsConfirmed(8).reservationsExpired(1)
                .paymentsSucceeded(8).paymentsWithFailure(1).refundsCompleted(1)
                .ticketsIssued(16).ticketsRevoked(2).ticketsScanned(9)
                .updatedAt(PROJECTED_AT).build();
    }

    private static DailyRevenueMetric revenueRow(
            String currency, long payOk, long refunds, long gross, long refunded) {
        return DailyRevenueMetric.builder()
                .metricDate(SEP_06).eventId(EVENT_ID).eventSessionId(S1).currency(currency)
                .paymentsSucceeded(payOk).refundsCompleted(refunds)
                .grossRevenueMinor(gross).refundedRevenueMinor(refunded)
                .updatedAt(PROJECTED_AT).build();
    }

    private static AnalyticsSessionFact sessionFact(String title, String label) {
        return AnalyticsSessionFact.builder()
                .eventSessionId(S1).eventId(EVENT_ID)
                .eventTitle(title).sessionLabel(label)
                .lastSourceEventAt(SOURCE_AT).updatedAt(SOURCE_AT).build();
    }
}
