package com.seatflow.analytics.integration;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.model.entity.DailyRevenueMetric;
import com.seatflow.analytics.model.entity.EventSessionMetric;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.DailyRevenueMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.service.impl.AnalyticsCsvExportServiceImpl;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.common.domain.enums.ErrorCode;
import com.seatflow.common.domain.exception.ValidationException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TASK-P14-006 §6.8–§6.10 acceptance suite over real PostgreSQL.
 *
 * <p>Proves the long-form grain split end to end: one session/date with RON + EUR
 * activity yields exactly one currency-neutral OPERATIONS row plus one REVENUE row per
 * currency, with deterministic ordering, formula-safe snapshots, and no PII.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AnalyticsAdminQueryRepository.class, AnalyticsCsvExportServiceImpl.class})
class AnalyticsCsvExportIntegrationTest {

    static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID S1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID S2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    static final LocalDate SEP_01 = LocalDate.of(2026, 9, 1);
    static final LocalDate SEP_05 = LocalDate.of(2026, 9, 5);
    static final LocalDate SEP_06 = LocalDate.of(2026, 9, 6);
    static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    static final Instant S1_PROJECTED = Instant.parse("2026-09-06T11:30:00Z");
    static final Instant S2_SOURCE = Instant.parse("2026-09-05T09:00:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_006_export_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private AnalyticsCsvExportServiceImpl exportService;

    @Autowired
    private AnalyticsAdminQueryRepository adminQueries;

    @Autowired
    private DailyOperationalMetricRepository dailyOperational;

    @Autowired
    private DailyRevenueMetricRepository dailyRevenue;

    @Autowired
    private AnalyticsSessionFactRepository sessionFacts;

    @Autowired
    private EventSessionMetricRepository sessionMetrics;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedFixtures() {
        sessionFacts.save(AnalyticsSessionFact.builder()
                .eventSessionId(S1).eventId(EVENT_ID)
                .eventTitle("Hamlet").sessionLabel("Evening")
                .startsAt(Instant.parse("2026-09-06T19:00:00Z")).status("SCHEDULED")
                .lastSourceEventAt(Instant.parse("2026-09-06T10:00:00Z")).updatedAt(NOW).build());
        sessionFacts.save(AnalyticsSessionFact.builder()
                .eventSessionId(S2).eventId(EVENT_ID)
                .eventTitle("=HYPERLINK(\"https://example.invalid\")")
                .sessionLabel("Line1\nLine2")
                .startsAt(Instant.parse("2026-09-05T14:00:00Z")).status("SCHEDULED")
                .lastSourceEventAt(S2_SOURCE).updatedAt(NOW).build());

        // S1 owns a lifetime recency marker; S2 falls back to its fact source time.
        sessionMetrics.save(EventSessionMetric.builder()
                .eventSessionId(S1).eventId(EVENT_ID)
                .lastProjectedEventAt(S1_PROJECTED).updatedAt(NOW).build());

        dailyOperational.save(operationalRow(SEP_06, S1, 10, 8, 1, 8, 1, 1, 16, 2, 9));
        dailyOperational.save(operationalRow(SEP_05, S2, 1, 0, 0, 0, 0, 0, 2, 0, 0));
        dailyOperational.save(operationalRow(SEP_01, S1, 7, 7, 0, 7, 0, 0, 14, 0, 0));

        dailyRevenue.save(revenueRow(SEP_06, S1, "RON", 5, 1, 1_000_000L, 100_000L));
        dailyRevenue.save(revenueRow(SEP_06, S1, "EUR", 3, 0, 500_000L, 0L));
        dailyRevenue.save(revenueRow(SEP_05, S2, "RON", 2, 0, 200_000L, 0L));
        dailyRevenue.save(revenueRow(SEP_01, S1, "EUR", 9, 0, 900_000L, 0L));
    }

    private static DailyOperationalMetric operationalRow(
            LocalDate date, UUID session,
            long created, long confirmed, long expired,
            long payOk, long payFail, long refunds,
            long issued, long revoked, long scanned) {
        return DailyOperationalMetric.builder()
                .metricDate(date).eventId(EVENT_ID).eventSessionId(session)
                .reservationsCreated(created).reservationsConfirmed(confirmed).reservationsExpired(expired)
                .paymentsSucceeded(payOk).paymentsWithFailure(payFail).refundsCompleted(refunds)
                .ticketsIssued(issued).ticketsRevoked(revoked).ticketsScanned(scanned)
                .updatedAt(NOW).build();
    }

    private static DailyRevenueMetric revenueRow(
            LocalDate date, UUID session, String currency,
            long payOk, long refunds, long gross, long refunded) {
        return DailyRevenueMetric.builder()
                .metricDate(date).eventId(EVENT_ID).eventSessionId(session).currency(currency)
                .paymentsSucceeded(payOk).refundsCompleted(refunds)
                .grossRevenueMinor(gross).refundedRevenueMinor(refunded)
                .updatedAt(NOW).build();
    }

    private static String line(String... cells) {
        return String.join(",", cells);
    }

    @Test
    @DisplayName("export preserves OPERATIONS/REVENUE grain with stable order and escaping")
    void shouldExportBoundedDeterministicGrainSplit() {
        byte[] bytes = exportService.exportDailyCsv(new AnalyticsDateRange(SEP_05, SEP_06), null, null);
        String csv = new String(bytes, StandardCharsets.UTF_8);

        String header = line("row_type", "metric_date", "event_id", "event_session_id",
                "event_title", "session_label", "currency", "reservations_created",
                "reservations_confirmed", "reservations_expired", "operational_payments_succeeded",
                "payments_with_failure", "operational_refunds_completed", "tickets_issued",
                "tickets_revoked", "tickets_scanned", "financial_payments_succeeded",
                "financial_refunds_completed", "gross_revenue_minor", "refunded_revenue_minor",
                "net_revenue_minor", "stripe_test_mode", "last_projected_event_at");
        // S2 SEP_05: malicious title guarded, multiline label quoted, fact recency fallback.
        String s2ops = line("OPERATIONS", "2026-09-05", EVENT_ID.toString(), S2.toString(),
                "\"'=HYPERLINK(\"\"https://example.invalid\"\")\"", "\"Line1\nLine2\"", "",
                "1", "0", "0", "0", "0", "0", "2", "0", "0",
                "", "", "", "", "", "", S2_SOURCE.toString());
        String s2rev = line("REVENUE", "2026-09-05", EVENT_ID.toString(), S2.toString(),
                "\"'=HYPERLINK(\"\"https://example.invalid\"\")\"", "\"Line1\nLine2\"", "RON",
                "", "", "", "", "", "", "", "", "", "2", "0",
                "200000", "0", "200000", "true", S2_SOURCE.toString());
        // S1 SEP_06: one OPERATIONS row plus RON and EUR REVENUE rows (currency ASC).
        String s1ops = line("OPERATIONS", "2026-09-06", EVENT_ID.toString(), S1.toString(),
                "Hamlet", "Evening", "",
                "10", "8", "1", "8", "1", "1", "16", "2", "9",
                "", "", "", "", "", "", S1_PROJECTED.toString());
        String s1eur = line("REVENUE", "2026-09-06", EVENT_ID.toString(), S1.toString(),
                "Hamlet", "Evening", "EUR",
                "", "", "", "", "", "", "", "", "", "3", "0",
                "500000", "0", "500000", "true", S1_PROJECTED.toString());
        String s1ron = line("REVENUE", "2026-09-06", EVENT_ID.toString(), S1.toString(),
                "Hamlet", "Evening", "RON",
                "", "", "", "", "", "", "", "", "", "5", "1",
                "1000000", "100000", "900000", "true", S1_PROJECTED.toString());

        assertThat(csv).isEqualTo(
                header + "\n" + s2ops + "\n" + s2rev + "\n" + s1ops + "\n" + s1eur + "\n" + s1ron + "\n");
    }

    @Test
    @DisplayName("session scope narrows the export to that session only")
    void shouldHonorSessionScope() {
        byte[] bytes = exportService.exportDailyCsv(new AnalyticsDateRange(SEP_05, SEP_06), null, S2);
        String csv = new String(bytes, StandardCharsets.UTF_8);

        // Row starts, not raw lines: S2's quoted label embeds a newline by design.
        assertThat(csv.lines().filter(line ->
                line.startsWith("OPERATIONS,") || line.startsWith("REVENUE,")).count())
                .isEqualTo(2L);
        assertThat(csv).contains(S2.toString());
        assertThat(csv).doesNotContain(S1.toString());
    }

    @Test
    @DisplayName("OPERATIONS rows carry counts only; REVENUE rows carry money, currency, and Test Mode")
    void shouldKeepOperationalAndFinancialGrainsExclusive() {
        // TASK-P14-007 §7.14: S1/SEP_06 owns RON + EUR activity, so the export must hold
        // exactly one OPERATIONS row plus one REVENUE row per currency, with no mixed
        // total and no double-countable repetition of operational counts.
        byte[] bytes = exportService.exportDailyCsv(new AnalyticsDateRange(SEP_05, SEP_06), null, null);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        List<String> rows = csv.lines()
                .filter(line -> line.startsWith("OPERATIONS,") || line.startsWith("REVENUE,"))
                .toList();

        List<String> s1Sep6 = rows.stream()
                .filter(line -> line.contains(S1.toString()) && line.contains("2026-09-06"))
                .toList();
        assertThat(s1Sep6).hasSize(3);

        String operations = s1Sep6.stream()
                .filter(line -> line.startsWith("OPERATIONS,")).toList().getFirst();
        String[] opsCells = operations.split(",", -1);
        assertThat(opsCells[6]).isEmpty(); // currency empty on OPERATIONS
        assertThat(opsCells[7]).isEqualTo("10"); // reservations_created once, not per currency
        // Financial cells (16..20) empty on OPERATIONS.
        for (int i = 16; i <= 20; i++) {
            assertThat(opsCells[i]).isEmpty();
        }
        assertThat(opsCells[21]).isEmpty(); // stripe_test_mode only on REVENUE

        List<String> revenues = s1Sep6.stream()
                .filter(line -> line.startsWith("REVENUE,")).toList();
        assertThat(revenues).hasSize(2);
        for (String revenue : revenues) {
            String[] cells = revenue.split(",", -1);
            // Operational count cells (7..15) empty on REVENUE so counts cannot double-count.
            for (int i = 7; i <= 15; i++) {
                assertThat(cells[i]).isEmpty();
            }
            assertThat(cells[21]).isEqualTo("true"); // stripe_test_mode on every REVENUE row
        }
        assertThat(revenues.get(0)).contains(",EUR,");
        assertThat(revenues.get(1)).contains(",RON,");
        assertThat(revenues.get(1)).contains(",900000,"); // RON net = 1000000 - 100000
        // No mixed-currency total (1000000 + 500000) exists anywhere in the export.
        assertThat(csv).doesNotContain(",1500000,");
        // No PII anywhere in the export.
        assertThat(csv).doesNotContain("customerEmail");
        assertThat(csv).doesNotContain("customerName");
    }

    @Test
    @DisplayName("exactly 10_000 real rows are accepted with a complete file")
    void shouldAcceptExactly10000RealRows() {
        // REV-001: isolated October date, so the September fixtures cannot leak in.
        LocalDate day = LocalDate.of(2026, 10, 1);
        seedBulkOperational(day, 1, 10_000);

        byte[] bytes = exportService.exportDailyCsv(new AnalyticsDateRange(day, day), null, null);
        String csv = new String(bytes, StandardCharsets.UTF_8);

        assertThat(csv.lines().count()).isEqualTo(10_001L);
        assertThat(csv).startsWith("row_type,metric_date,");
    }

    @Test
    @DisplayName("10_001 real rows are rejected with ANALYTICS_EXPORT_TOO_LARGE and no CSV body")
    void shouldReject10001RealRows() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        seedBulkOperational(day, 1, 10_001);

        assertThatThrownBy(
                        () -> exportService.exportDailyCsv(new AnalyticsDateRange(day, day), null, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYTICS_EXPORT_TOO_LARGE));
    }

    @Test
    @DisplayName("a row committed between preflight and retrieval cannot produce an over-limit file")
    void shouldNeverExceedBoundWhenRowCommitsBetweenPreflightAndLoad() {
        // REV-001 regression: the preflight observes exactly 10_000 rows, then a
        // projection consumer commits one more matching row before retrieval.
        // The capped loads plus the authoritative post-load union check must
        // turn this window into a clean rejection — never an 10_001-row file.
        LocalDate day = LocalDate.of(2026, 10, 3);
        seedBulkOperational(day, 1, 10_000);

        AnalyticsAdminQueryRepository stalePreflight = Mockito.spy(adminQueries);
        Mockito.doReturn(10_000L).when(stalePreflight)
                .countOperationalRows(day, day, null, null);
        Mockito.doReturn(0L).when(stalePreflight)
                .countRevenueRows(day, day, null, null);
        dailyOperational.saveAndFlush(DailyOperationalMetric.builder()
                .metricDate(day).eventId(EVENT_ID).eventSessionId(new UUID(7L, 10_001))
                .reservationsCreated(1).updatedAt(NOW).build());
        AnalyticsCsvExportServiceImpl exportWithStalePreflight =
                new AnalyticsCsvExportServiceImpl(stalePreflight, sessionFacts, sessionMetrics);

        assertThatThrownBy(() -> exportWithStalePreflight
                        .exportDailyCsv(new AnalyticsDateRange(day, day), null, null))
                .isInstanceOf(ValidationException.class)
                .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ANALYTICS_EXPORT_TOO_LARGE));
    }

    @Test
    @DisplayName("a row committed after the reader's first statement stays invisible inside REPEATABLE_READ")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldHoldBoundedSnapshotWhenRowCommitsMidTransaction() throws InterruptedException {
        // REV-007: true concurrent transactions against real PostgreSQL. The
        // reader opens an explicit REPEATABLE_READ transaction (the isolation
        // the proxied service method declares) and pins its snapshot with a
        // first statement; a writer transaction then commits one more matching
        // row on another thread. The Spring-proxied service call joins the
        // reader transaction (REQUIRED propagation, no self-invocation bypass)
        // and must therefore still observe exactly 10_000 rows — a complete
        // 10_001-line file, never an over-limit response. No counts are
        // stubbed and the service is never constructed directly.
        assertRepeatableReadExport();
        LocalDate day = LocalDate.of(2026, 11, 10);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> seedBulkOperational(day, 1, 10_000));

        CountDownLatch snapshotPinned = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                assertThat(snapshotPinned.await(30, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        dailyOperational.saveAndFlush(DailyOperationalMetric.builder()
                                .metricDate(day).eventId(EVENT_ID).eventSessionId(new UUID(8L, 10_001))
                                .reservationsCreated(1).updatedAt(NOW).build()));
                writerCommitted.countDown();
            } catch (Throwable ex) {
                writerFailure.set(ex);
                writerCommitted.countDown();
            }
        });
        writer.setDaemon(true);
        writer.start();

        TransactionTemplate readerTx = new TransactionTemplate(transactionManager);
        readerTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        byte[] bytes;
        try {
            bytes = readerTx.execute(status -> {
                // First statement pins the REPEATABLE_READ snapshot at 10_000 rows.
                assertThat(adminQueries.countOperationalRows(day, day, null, null)).isEqualTo(10_000L);
                snapshotPinned.countDown();
                try {
                    assertThat(writerCommitted.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("writer commit wait interrupted", ex);
                }
                // Joins the reader transaction through the Spring proxy, so the
                // writer's independently committed row must stay invisible here.
                return exportService.exportDailyCsv(new AnalyticsDateRange(day, day), null, null);
            });

            writer.join(30_000L);
            assertThat(writerFailure.get()).isNull();
            assertThat(bytes).isNotNull();
            assertThat(new String(bytes, StandardCharsets.UTF_8).lines().count()).isEqualTo(10_001L);
            // The writer row really did commit independently — it is visible to a
            // fresh transaction even though the reader snapshot never saw it.
            assertThat(adminQueries.countOperationalRows(day, day, null, null)).isEqualTo(10_001L);
        } finally {
            // NOT_SUPPORTED tests commit: remove every committed row (including
            // the @BeforeEach fixtures this run committed) so the rollback-based
            // tests in this class observe their isolated state.
            deleteCommittedFixtures();
        }
    }

    @Test
    @DisplayName("a row committed before a standalone proxied call rejects with no partial file")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRejectOverLimitThroughProxyWhenRowCommitsFirst() {
        // REV-007: the service-owned REPEATABLE_READ transaction taken through
        // the Spring proxy (no test-managed transaction, no stubs, no direct
        // construction) observes the fully committed 10_001 rows and fails
        // with the stable error before any CSV bytes are built.
        assertRepeatableReadExport();
        LocalDate day = LocalDate.of(2026, 11, 11);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                seedBulkOperational(day, 1, 10_001);
            });

            assertThat(adminQueries.countOperationalRows(day, day, null, null)).isEqualTo(10_001L);
            assertThatThrownBy(
                            () -> exportService.exportDailyCsv(new AnalyticsDateRange(day, day), null, null))
                    .isInstanceOf(ValidationException.class)
                    .satisfies(ex -> assertThat(((ValidationException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ANALYTICS_EXPORT_TOO_LARGE));
        } finally {
            deleteCommittedFixtures();
        }
    }

    /**
     * Structural guard for REV-007: the export entry point must stay a public
     * proxy-invoked {@code REPEATABLE_READ} transaction, otherwise the
     * snapshot behavior above silently degrades to per-statement snapshots.
     */
    private static void assertRepeatableReadExport() {
        try {
            var method = AnalyticsCsvExportServiceImpl.class
                    .getMethod("exportDailyCsv", AnalyticsDateRange.class, UUID.class, UUID.class);
            var tx = method.getAnnotation(Transactional.class);
            assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(tx).isNotNull();
            assertThat(tx.isolation())
                    .isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("export entry point signature changed", ex);
        }
    }

    /**
     * REV-007 isolation guard: the true-concurrency tests below run outside
     * the test-managed transaction, so every row they (and their
     * {@code @BeforeEach} run) committed is deleted here. The pre-existing
     * rollback-based tests in this class are therefore unaffected regardless
     * of execution order.
     */
    private void deleteCommittedFixtures() {
        TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
        cleanup.executeWithoutResult(status -> {
            dailyOperational.deleteAll();
            dailyRevenue.deleteAll();
            sessionFacts.deleteAll();
            sessionMetrics.deleteAll();
        });
    }

    /**
     * Bulk-seed {@code to - from + 1} operational grains with distinct sessions
     * on one isolated date, flushing in chunks so the persistence context stays
     * bounded.
     */    private void seedBulkOperational(LocalDate day, int from, int to) {
        List<DailyOperationalMetric> chunk = new ArrayList<>(1000);
        for (int i = from; i <= to; i++) {
            chunk.add(DailyOperationalMetric.builder()
                    .metricDate(day).eventId(EVENT_ID).eventSessionId(new UUID(7L, i))
                    .reservationsCreated(1).updatedAt(NOW).build());
            if (chunk.size() == 1000) {
                dailyOperational.saveAllAndFlush(chunk);
                entityManager.clear();
                chunk = new ArrayList<>(1000);
            }
        }
        if (!chunk.isEmpty()) {
            dailyOperational.saveAllAndFlush(chunk);
            entityManager.clear();
        }
    }
}
