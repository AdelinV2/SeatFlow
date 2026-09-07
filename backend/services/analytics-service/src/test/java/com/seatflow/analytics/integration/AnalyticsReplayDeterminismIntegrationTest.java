package com.seatflow.analytics.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ProjectionEventProcessor;
import com.seatflow.analytics.repository.AnalyticsPaymentFactRepository;
import com.seatflow.analytics.repository.AnalyticsReservationFactRepository;
import com.seatflow.analytics.repository.AnalyticsSessionFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketFactRepository;
import com.seatflow.analytics.repository.AnalyticsTicketRevocationFactRepository;
import com.seatflow.analytics.repository.DailyOperationalMetricRepository;
import com.seatflow.analytics.repository.DailyRevenueMetricRepository;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.repository.ProcessedEventRepository;
import com.seatflow.analytics.support.AnalyticsCanonicalFixtures;
import com.seatflow.analytics.support.AnalyticsProjectionTestConfig;
import com.seatflow.analytics.support.AnalyticsSnapshot;
import com.seatflow.common.events.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-P14-007 section 7.11 acceptance suite over real PostgreSQL.
 *
 * <p>Rebuilds the canonical retained baseline ({@link AnalyticsCanonicalFixtures#fullBaseline})
 * from an empty analytics read model and proves the normalized snapshot (all business facts
 * and aggregates, excluding generated processing timestamps) is identical. A duplicate
 * redelivery of the retained set is a further no-op with an identical snapshot.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AnalyticsProjectionTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalyticsReplayDeterminismIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_007_replay_test")
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
    private ProjectionEventProcessor processor;

    @Autowired
    private AnalyticsReservationFactRepository reservationFacts;

    @Autowired
    private AnalyticsPaymentFactRepository paymentFacts;

    @Autowired
    private AnalyticsTicketFactRepository ticketFacts;

    @Autowired
    private AnalyticsTicketRevocationFactRepository revocationFacts;

    @Autowired
    private AnalyticsSessionFactRepository sessionFacts;

    @Autowired
    private EventSessionMetricRepository sessionMetrics;

    @Autowired
    private EventSessionRevenueMetricRepository sessionRevenue;

    @Autowired
    private DailyOperationalMetricRepository dailyOperational;

    @Autowired
    private DailyRevenueMetricRepository dailyRevenue;

    @Autowired
    private ProcessedEventRepository processedEvents;

    private AnalyticsSnapshot.Driver driver;

    @BeforeEach
    void cleanState() {
        driver = new AnalyticsSnapshot.Driver(processor);
        clearReadModel();
    }

    @Test
    @DisplayName("7.11 rebuild from the retained set on empty state converges to the same snapshot")
    void rebuildFromEmptyStateConverges() {
        List<EventEnvelope<JsonNode>> retained = AnalyticsCanonicalFixtures.fullBaseline();
        driver.processAll(retained);
        String first = snapshot();
        assertThat(first).isNotBlank();

        // Clear/recreate ONLY analytics read-model state (never another service database).
        clearReadModel();

        driver.processAll(retained);
        assertThat(snapshot()).isEqualTo(first);
    }

    @Test
    @DisplayName("7.11 reordered session blocks rebuild to the same normalized snapshot")
    void reorderedRebuildConverges() {
        driver.processAll(AnalyticsCanonicalFixtures.fullBaseline());
        String first = snapshot();

        clearReadModel();

        // Same retained events, session blocks in a different order: final state must agree.
        driver.processAll(AnalyticsCanonicalFixtures.fullBaselineSessionReordered());
        assertThat(snapshot()).isEqualTo(first);
    }

    @Test
    @DisplayName("7.11 duplicate redelivery of the retained set is a no-op with equal snapshot")
    void duplicateRedeliverySnapshotEquality() {
        List<EventEnvelope<JsonNode>> retained = AnalyticsCanonicalFixtures.fullBaseline();
        driver.processAll(retained);
        String first = snapshot();
        long markers = processedEvents.count();

        List<ProjectionEventProcessor.Outcome> outcomes =
                retained.stream().map(driver::process).toList();
        assertThat(outcomes).allMatch(o -> o == ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(snapshot()).isEqualTo(first);
        assertThat(processedEvents.count()).isEqualTo(markers);
    }

    @Test
    @DisplayName("7.11 canonical baseline carries the exact S1/S2/S3/S4/P6 expectations")
    void canonicalBaselineExpectations() {
        driver.processAll(AnalyticsCanonicalFixtures.fullBaseline());

        // S1: gross 20000, refunded 20000, net 0.
        var s1Revenue = sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S1);
        assertThat(s1Revenue).hasSize(1);
        assertThat(s1Revenue.getFirst().getGrossRevenueMinor())
                .isEqualTo(AnalyticsCanonicalFixtures.S1_GROSS_MINOR);
        assertThat(s1Revenue.getFirst().getRefundedRevenueMinor())
                .isEqualTo(AnalyticsCanonicalFixtures.S1_REFUNDED_MINOR);
        assertThat(s1Revenue.getFirst().netRevenueMinor())
                .isEqualTo(AnalyticsCanonicalFixtures.S1_NET_MINOR);

        var s1 = sessionMetrics.findById(AnalyticsCanonicalFixtures.S1).orElseThrow();
        assertThat(s1.getPaymentsSucceeded()).isOne();
        assertThat(s1.getRefundsCompleted()).isOne();
        assertThat(s1.getTicketsIssued()).isEqualTo(2L);
        assertThat(s1.getTicketsRevoked()).isEqualTo(2L);
        assertThat(s1.getTicketsScanned()).isOne();
        assertThat(s1.getReservationsCreated()).isOne();
        assertThat(s1.getReservationsConfirmed()).isOne();

        // S2: created + expired, zero money.
        var s2 = sessionMetrics.findById(AnalyticsCanonicalFixtures.S2).orElseThrow();
        assertThat(s2.getReservationsCreated()).isOne();
        assertThat(s2.getReservationsExpired()).isOne();
        assertThat(sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S2)).isEmpty();

        // S3: EUR gross 5000.
        var s3Revenue = sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S3);
        assertThat(s3Revenue).hasSize(1);
        assertThat(s3Revenue.getFirst().getCurrency()).isEqualTo("EUR");
        assertThat(s3Revenue.getFirst().getGrossRevenueMinor())
                .isEqualTo(AnalyticsCanonicalFixtures.S3_GROSS_MINOR);

        // P6 failure session: failure counted, gross untouched.
        var s5 = sessionMetrics.findById(AnalyticsCanonicalFixtures.S5).orElseThrow();
        assertThat(s5.getPaymentsWithFailure()).isOne();
        assertThat(s5.getPaymentsSucceeded()).isZero();
        assertThat(sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S5)).isEmpty();
    }

    private void clearReadModel() {
        dailyRevenue.deleteAll();
        dailyOperational.deleteAll();
        sessionRevenue.deleteAll();
        sessionMetrics.deleteAll();
        revocationFacts.deleteAll();
        ticketFacts.deleteAll();
        paymentFacts.deleteAll();
        reservationFacts.deleteAll();
        sessionFacts.deleteAll();
        processedEvents.deleteAll();
    }

    private String snapshot() {
        return AnalyticsSnapshot.snapshot(reservationFacts, paymentFacts, ticketFacts,
                revocationFacts, sessionFacts, sessionMetrics, sessionRevenue,
                dailyOperational, dailyRevenue);
    }
}
