package com.seatflow.analytics.integration;

import com.seatflow.analytics.model.entity.AnalyticsSessionFact;
import com.seatflow.analytics.model.entity.DailyOperationalMetric;
import com.seatflow.analytics.repository.AnalyticsAdminQueryRepository;
import com.seatflow.analytics.repository.AnalyticsProjectionQueryRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.service.impl.AdminAnalyticsQueryServiceImpl;
import com.seatflow.analytics.web.dto.request.AnalyticsDateRange;
import com.seatflow.analytics.web.dto.response.AnalyticsEventFilterOptionResponse;
import com.seatflow.analytics.web.dto.response.AnalyticsSessionFilterOptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-P14-006 §6.1–§6.3 acceptance suite over real PostgreSQL.
 *
 * <p>Proves filter options come from analytics facts/aggregates only, are explicitly
 * bounded with honest truncation metadata, sort deterministically, scope sessions by
 * event, and answer unknown events with a 200 empty envelope.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        AnalyticsAdminQueryRepository.class,
        AnalyticsProjectionQueryRepository.class,
        AdminAnalyticsQueryServiceImpl.class,
        AnalyticsFilterOptionsIntegrationTest.TestConfig.class
})
class AnalyticsFilterOptionsIntegrationTest {

    static final UUID E1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID E2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    static final UUID E3 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    static final UUID E4_OUT_OF_RANGE = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    static final UUID S1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID S2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID S3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID S4_NO_FACT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID UNKNOWN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    static final LocalDate SEP_01 = LocalDate.of(2026, 9, 1);
    static final LocalDate SEP_05 = LocalDate.of(2026, 9, 5);
    static final LocalDate SEP_06 = LocalDate.of(2026, 9, 6);
    static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_006_options_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        Clock analyticsClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private AdminAnalyticsQueryServiceImpl service;

    @Autowired
    private AnalyticsAdminQueryRepository adminQueries;

    @Autowired
    private AnalyticsProjectionQueryRepository projectionQueries;

    @Autowired
    private DailyOperationalMetricRepository dailyOperational;

    @Autowired
    private AnalyticsSessionFactRepository sessionFacts;

    @Autowired
    private EventSessionMetricRepository sessionMetrics;

    @Autowired
    private EventSessionRevenueMetricRepository sessionRevenue;

    @Autowired
    private Clock analyticsClock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void seedFixtures() {
        sessionFacts.save(fact(S1, E1, "Zulu Gala", "Evening", "2026-09-06T19:00:00Z"));
        sessionFacts.save(fact(S2, E1, "Zulu Gala", "Matinee", "2026-09-05T14:00:00Z"));
        sessionFacts.save(fact(S3, E2, "Alpha Fest", "Main", "2026-09-06T10:00:00Z"));
        // S4 deliberately has no session fact: null label must stay null, never fetched live.

        dailyOperational.save(operationalRow(E1, SEP_06, S1));
        dailyOperational.save(operationalRow(E1, SEP_05, S2));
        dailyOperational.save(operationalRow(E2, SEP_06, S3));
        dailyOperational.save(operationalRow(E3, SEP_06, S4_NO_FACT));
        dailyOperational.save(operationalRow(E4_OUT_OF_RANGE, SEP_01,
                UUID.fromString("55555555-5555-5555-5555-555555555555")));
    }

    private static AnalyticsSessionFact fact(
            UUID session, UUID event, String title, String label, String startsAt) {
        return AnalyticsSessionFact.builder()
                .eventSessionId(session).eventId(event)
                .eventTitle(title).sessionLabel(label)
                .startsAt(Instant.parse(startsAt)).status("SCHEDULED")
                .lastSourceEventAt(NOW).updatedAt(NOW).build();
    }

    private static DailyOperationalMetric operationalRow(UUID event, LocalDate date, UUID session) {
        return DailyOperationalMetric.builder()
                .metricDate(date).eventId(event).eventSessionId(session)
                .reservationsCreated(1).updatedAt(NOW).build();
    }

    private AnalyticsDateRange range() {
        return new AnalyticsDateRange(SEP_05, SEP_06);
    }

    @Test
    @DisplayName("event options sort null-label first, then label, then eventId; out-of-range excluded")
    void shouldSortEventOptionsDeterministically() {
        var envelope = service.getEventFilterOptions(range());

        assertThat(envelope.truncated()).isFalse();
        assertThat(envelope.totalProjected()).isEqualTo(3);
        List<UUID> ids = envelope.items().stream()
                .map(AnalyticsEventFilterOptionResponse::eventId).toList();
        assertThat(ids).containsExactly(E3, E2, E1);

        assertThat(envelope.items().get(0).label()).isNull();
        assertThat(envelope.items().get(0).firstProjectedSessionStart()).isNull();
        assertThat(envelope.items().get(1).label()).isEqualTo("Alpha Fest");
        assertThat(envelope.items().get(1).firstProjectedSessionStart())
                .isEqualTo(Instant.parse("2026-09-06T10:00:00Z"));
        assertThat(envelope.items().get(2).label()).isEqualTo("Zulu Gala");
        assertThat(envelope.items().get(2).firstProjectedSessionStart())
                .isEqualTo(Instant.parse("2026-09-05T14:00:00Z"));
    }

    @Test
    @DisplayName("session options sort startsAt ASC NULLS LAST with sessionId tiebreak")
    void shouldSortSessionOptionsByStartThenId() {
        var envelope = service.getSessionFilterOptions(range(), null);

        assertThat(envelope.truncated()).isFalse();
        assertThat(envelope.totalProjected()).isEqualTo(4);
        List<UUID> ids = envelope.items().stream()
                .map(AnalyticsSessionFilterOptionResponse::eventSessionId).toList();
        assertThat(ids).containsExactly(S2, S3, S1, S4_NO_FACT);

        assertThat(envelope.items().get(3).label()).isNull();
        assertThat(envelope.items().get(3).startsAt()).isNull();
        assertThat(envelope.items().get(3).eventId()).isEqualTo(E3);
    }

    @Test
    @DisplayName("eventId scopes sessions; unknown event answers 200 empty envelope")
    void shouldScopeSessionsByEvent() {
        var scoped = service.getSessionFilterOptions(range(), E1);

        assertThat(scoped.truncated()).isFalse();
        assertThat(scoped.totalProjected()).isEqualTo(2);
        assertThat(scoped.items().stream()
                .map(AnalyticsSessionFilterOptionResponse::eventSessionId).toList())
                .containsExactly(S2, S1);

        var unknown = service.getSessionFilterOptions(range(), UNKNOWN);

        assertThat(unknown.items()).isEmpty();
        assertThat(unknown.totalProjected()).isZero();
        assertThat(unknown.truncated()).isFalse();
    }

    @Test
    @DisplayName("501 matches return 500 items with truncated=true and exact totalProjected")
    void shouldTruncateExplicitlyBeyond500() {
        List<DailyOperationalMetric> bulk = new ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            bulk.add(DailyOperationalMetric.builder()
                    .metricDate(SEP_06)
                    .eventId(new UUID(0L, i))
                    .eventSessionId(new UUID(1L, i))
                    .reservationsCreated(1).updatedAt(NOW).build());
        }
        dailyOperational.saveAll(bulk);

        var envelope = service.getEventFilterOptions(range());

        assertThat(envelope.totalProjected()).isEqualTo(504);
        assertThat(envelope.truncated()).isTrue();
        assertThat(envelope.items()).hasSize(500);
        // Null labels sort before Alpha/Zulu; tiny generated IDs sort before the seed IDs.
        assertThat(envelope.items().get(0).eventId()).isEqualTo(new UUID(0L, 1L));
        assertThat(envelope.items().get(499).eventId()).isEqualTo(new UUID(0L, 500L));
    }

    @Test
    @DisplayName("exactly 500 matches report an exact total with truncated=false")
    void shouldAcceptExactly500WithoutTruncation() {
        // REV-004: isolated October date, so the September fixtures cannot leak in.
        LocalDate day = LocalDate.of(2026, 10, 5);
        List<DailyOperationalMetric> bulk = new ArrayList<>();
        for (long i = 1; i <= 500; i++) {
            bulk.add(DailyOperationalMetric.builder()
                    .metricDate(day)
                    .eventId(new UUID(2L, i))
                    .eventSessionId(new UUID(3L, i))
                    .reservationsCreated(1).updatedAt(NOW).build());
        }
        dailyOperational.saveAll(bulk);

        var envelope = service.getEventFilterOptions(new AnalyticsDateRange(day, day));

        assertThat(envelope.totalProjected()).isEqualTo(500);
        assertThat(envelope.truncated()).isFalse();
        assertThat(envelope.items()).hasSize(500);
    }

    @Test
    @DisplayName("a stale 500 event count cannot hide a 501st event committed before the probe")
    void shouldStayHonestWhenEventCommitsBetweenCountAndProbe() {
        // REV-004 regression: the count observes 500 events, then a projection
        // consumer commits a 501st matching event before the probe. The probe
        // independently forces truncated=true with at least 501 total — never
        // the hidden-truncation envelope totalProjected=500/truncated=false.
        LocalDate day = LocalDate.of(2026, 10, 6);
        List<DailyOperationalMetric> bulk = new ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            bulk.add(DailyOperationalMetric.builder()
                    .metricDate(day)
                    .eventId(new UUID(2L, i))
                    .eventSessionId(new UUID(3L, i))
                    .reservationsCreated(1).updatedAt(NOW).build());
        }
        dailyOperational.saveAll(bulk);

        AnalyticsAdminQueryRepository staleCount = Mockito.spy(adminQueries);
        Mockito.doReturn(500L).when(staleCount).countEventOptions(day, day);
        AdminAnalyticsQueryServiceImpl serviceWithStaleCount = new AdminAnalyticsQueryServiceImpl(
                staleCount, projectionQueries, sessionFacts,
                sessionMetrics, sessionRevenue, analyticsClock);

        var envelope = serviceWithStaleCount
                .getEventFilterOptions(new AnalyticsDateRange(day, day));

        assertThat(envelope.items()).hasSize(500);
        assertThat(envelope.truncated()).isTrue();
        assertThat(envelope.totalProjected()).isGreaterThanOrEqualTo(501L);
    }

    @Test
    @DisplayName("a stale 500 session count cannot hide a 501st session committed before the probe")
    void shouldStayHonestWhenSessionCommitsBetweenCountAndProbe() {
        // REV-004 regression for the session endpoint: same stale-count window
        // as the event endpoint above.
        LocalDate day = LocalDate.of(2026, 10, 7);
        UUID event = new UUID(4L, 0L);
        List<DailyOperationalMetric> bulk = new ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            bulk.add(DailyOperationalMetric.builder()
                    .metricDate(day)
                    .eventId(event)
                    .eventSessionId(new UUID(5L, i))
                    .reservationsCreated(1).updatedAt(NOW).build());
        }
        dailyOperational.saveAll(bulk);

        AnalyticsAdminQueryRepository staleCount = Mockito.spy(adminQueries);
        Mockito.doReturn(500L).when(staleCount).countSessionOptions(day, day, null);
        AdminAnalyticsQueryServiceImpl serviceWithStaleCount = new AdminAnalyticsQueryServiceImpl(
                staleCount, projectionQueries, sessionFacts,
                sessionMetrics, sessionRevenue, analyticsClock);

        var envelope = serviceWithStaleCount
                .getSessionFilterOptions(new AnalyticsDateRange(day, day), null);

        assertThat(envelope.items()).hasSize(500);
        assertThat(envelope.truncated()).isTrue();
        assertThat(envelope.totalProjected()).isGreaterThanOrEqualTo(501L);
    }

    @Test
    @DisplayName("a 501st event committed mid-transaction stays invisible inside REPEATABLE_READ")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldHoldSnapshotConsistentEventEnvelopeWhenEventCommitsMidTransaction()
            throws InterruptedException {
        // REV-007: true concurrent transactions against real PostgreSQL. The
        // reader pins its REPEATABLE_READ snapshot with a first statement; a
        // writer transaction then commits a 501st matching event on another
        // thread. The Spring-proxied service call joins the reader transaction
        // and must answer from that snapshot — 500 items, exact total 500,
        // truncated=false — with no stubbed counts and no direct construction.
        assertRepeatableReadOptions();
        LocalDate day = LocalDate.of(2026, 11, 12);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<DailyOperationalMetric> bulk = new ArrayList<>();
            for (long i = 1; i <= 500; i++) {
                bulk.add(DailyOperationalMetric.builder()
                        .metricDate(day)
                        .eventId(new UUID(6L, i))
                        .eventSessionId(new UUID(7L, i))
                        .reservationsCreated(1).updatedAt(NOW).build());
            }
            dailyOperational.saveAll(bulk);
        });

        CountDownLatch snapshotPinned = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                assertThat(snapshotPinned.await(30, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        dailyOperational.saveAndFlush(DailyOperationalMetric.builder()
                                .metricDate(day)
                                .eventId(new UUID(6L, 501))
                                .eventSessionId(new UUID(7L, 501))
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
        var envelope = readerTx.execute(status -> {
            // First statement pins the snapshot at exactly 500 events.
            assertThat(adminQueries.countEventOptions(day, day)).isEqualTo(500L);
            snapshotPinned.countDown();
            try {
                assertThat(writerCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("writer commit wait interrupted", ex);
            }
            return service.getEventFilterOptions(new AnalyticsDateRange(day, day));
        });

        try {
            writer.join(30_000L);
            assertThat(writerFailure.get()).isNull();
            // Snapshot-consistent: the independently committed 501st event is
            // invisible here, so items, exact total, and truncation agree.
            assertThat(envelope.items()).hasSize(500);
            assertThat(envelope.totalProjected()).isEqualTo(500L);
            assertThat(envelope.truncated()).isFalse();
            // The writer row really did commit independently: a fresh proxied
            // read observes 501 with honest truncation metadata.
            var fresh = service.getEventFilterOptions(new AnalyticsDateRange(day, day));
            assertThat(fresh.items()).hasSize(500);
            assertThat(fresh.truncated()).isTrue();
            assertThat(fresh.totalProjected()).isGreaterThanOrEqualTo(501L);
        } finally {
            deleteCommittedFixtures();
        }
    }

    @Test
    @DisplayName("a 501st session committed mid-transaction stays invisible inside REPEATABLE_READ")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldHoldSnapshotConsistentSessionEnvelopeWhenSessionCommitsMidTransaction()
            throws InterruptedException {
        // REV-007 session-endpoint counterpart of the event test above.
        assertRepeatableReadOptions();
        LocalDate day = LocalDate.of(2026, 11, 13);
        UUID event = new UUID(8L, 0L);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<DailyOperationalMetric> bulk = new ArrayList<>();
            for (long i = 1; i <= 500; i++) {
                bulk.add(DailyOperationalMetric.builder()
                        .metricDate(day)
                        .eventId(event)
                        .eventSessionId(new UUID(9L, i))
                        .reservationsCreated(1).updatedAt(NOW).build());
            }
            dailyOperational.saveAll(bulk);
        });

        CountDownLatch snapshotPinned = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                assertThat(snapshotPinned.await(30, TimeUnit.SECONDS)).isTrue();
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        dailyOperational.saveAndFlush(DailyOperationalMetric.builder()
                                .metricDate(day)
                                .eventId(event)
                                .eventSessionId(new UUID(9L, 501))
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
        var envelope = readerTx.execute(status -> {
            // First statement pins the snapshot at exactly 500 sessions.
            assertThat(adminQueries.countSessionOptions(day, day, event)).isEqualTo(500L);
            snapshotPinned.countDown();
            try {
                assertThat(writerCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("writer commit wait interrupted", ex);
            }
            return service.getSessionFilterOptions(new AnalyticsDateRange(day, day), event);
        });

        try {
            writer.join(30_000L);
            assertThat(writerFailure.get()).isNull();
            assertThat(envelope.items()).hasSize(500);
            assertThat(envelope.totalProjected()).isEqualTo(500L);
            assertThat(envelope.truncated()).isFalse();
            var fresh = service.getSessionFilterOptions(new AnalyticsDateRange(day, day), event);
            assertThat(fresh.items()).hasSize(500);
            assertThat(fresh.truncated()).isTrue();
            assertThat(fresh.totalProjected()).isGreaterThanOrEqualTo(501L);
        } finally {
            deleteCommittedFixtures();
        }
    }

    /**
     * Structural guard for REV-007: both filter entry points must stay public
     * proxy-invoked {@code REPEATABLE_READ} transactions, otherwise the
     * snapshot behavior above silently degrades to per-statement snapshots.
     */
    private static void assertRepeatableReadOptions() {
        try {
            var events = AdminAnalyticsQueryServiceImpl.class
                    .getMethod("getEventFilterOptions", AnalyticsDateRange.class);
            var sessions = AdminAnalyticsQueryServiceImpl.class
                    .getMethod("getSessionFilterOptions", AnalyticsDateRange.class, UUID.class);
            for (var method : List.of(events, sessions)) {
                var tx = method.getAnnotation(Transactional.class);
                assertThat(java.lang.reflect.Modifier.isPublic(method.getModifiers())).isTrue();
                assertThat(tx).isNotNull();
                assertThat(tx.isolation())
                        .isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
            }
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("filter entry point signature changed", ex);
        }
    }

    /**
     * REV-007 isolation guard: the true-concurrency tests above run outside
     * the test-managed transaction, so every row they (and their
     * {@code @BeforeEach} run) committed is deleted here. The pre-existing
     * rollback-based tests in this class are therefore unaffected regardless
     * of execution order.
     */
    private void deleteCommittedFixtures() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            dailyOperational.deleteAll();
            sessionFacts.deleteAll();
        });
    }
}
