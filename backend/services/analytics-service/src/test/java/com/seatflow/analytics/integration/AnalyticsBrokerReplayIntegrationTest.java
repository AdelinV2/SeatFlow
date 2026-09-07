package com.seatflow.analytics.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.config.KafkaConsumerConfig;
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
import com.seatflow.analytics.support.AnalyticsEnvelopeFactory;
import com.seatflow.analytics.support.AnalyticsSnapshot;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REV-002 bounded real-broker replay / cross-topic ordering proof (TASK-P14-007 §§7.6/7.11).
 *
 * <p>Unlike the direct-{@code Processor} convergence suites, this class publishes the canonical
 * retained event set to the real topic families through {@link KafkaTemplate}, lets the
 * production listener ({@code AnalyticsEventConsumer} + P14-002 error handler) parse, route,
 * and project it, then clears <em>only</em> analytics read-model state and replays the same
 * retained set in an alternate valid cross-topic order ({@code session-reordered} blocks;
 * same-topic producer order preserved). Normalized snapshots must agree.
 *
 * <p>Synchronization waits on the observable {@code processed_events} count with bounded
 * timeouts — never blind sleeps. The proof fails for a listener subscription/parser
 * regression (count never reaches the retained size), an incompatible envelope (DLQ without
 * marker), a listener that stops invoking the processor, a cross-topic order bug (business
 * snapshot mismatch), or a broker that a fresh {@code earliest} group cannot catch up from
 * (retention probe below).
 *
 * <h2>Full snapshot equality including session watermarks (REV-002 / REV-005)</h2>
 * <p>Equality covers the complete normalized snapshot — every business fact and aggregate
 * line ({@code RES/PAY/TIX/BREV/SES/SM/SR/DO/DR}). Only intentionally generated processing
 * timestamps ({@code updated_at}/{@code processed_at}, excluded by
 * {@link AnalyticsSnapshot#snapshot}) may differ. {@code SES|} session-correlation
 * watermarks ({@code lastSourceEventAt}) are source/business timestamps, so they must match
 * exactly: {@code TicketProjectionHandler.fillTicketCorrelation} reconciles the watermark
 * from the maximum source time of the session's correlated facts with a known-session
 * event fallback, making it order-independent (S3 converges to 11:00 whether the scan is
 * consumed before or after reservation correlation is resolvable). Both tests below pin
 * that watermark explicitly as the REV-005 regression guard.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(topics = {
        EventTopics.RESERVATION_EVENTS,
        EventTopics.PAYMENT_EVENTS,
        EventTopics.TICKET_EVENTS,
        EventTopics.EVENT_EVENTS,
        KafkaConsumerConfig.ANALYTICS_DLQ_TOPIC
}, partitions = 1)
class AnalyticsBrokerReplayIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_007_broker_replay_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    @BeforeEach
    void cleanState() {
        clearReadModel();
    }

    @Test
    @DisplayName("7.11 retained set replays via the broker to the same normalized snapshot")
    void retainedSetReplaysToSameSnapshotViaBroker() throws Exception {
        List<EventEnvelope<JsonNode>> retained = AnalyticsCanonicalFixtures.fullBaseline();
        publishAll(retained);

        await(Duration.ofSeconds(60), () -> processedMarkerCount() == retained.size());
        String first = snapshot();
        assertThat(first).isNotBlank();

        // A fresh earliest group must still see the retained history on every topic family:
        // proves broker retention + earliest catch-up for a rebuilt analytics consumer.
        assertRetainedHistoryReadable(
                Set.of(EventTopics.RESERVATION_EVENTS, EventTopics.PAYMENT_EVENTS,
                        EventTopics.TICKET_EVENTS));

        // Recreate/clear ONLY analytics read-model state (never another service database).
        clearReadModel();
        assertThat(processedMarkerCount()).isZero();

        // Replay the exact same retained set in an alternate valid cross-topic order.
        List<EventEnvelope<JsonNode>> reordered = AnalyticsCanonicalFixtures.fullBaselineSessionReordered();
        assertThat(reordered).hasSize(retained.size());
        publishAll(reordered);

        await(Duration.ofSeconds(60), () -> processedMarkerCount() == reordered.size());
        assertThat(snapshot()).isEqualTo(first);
        assertSessionWatermark(AnalyticsCanonicalFixtures.S3, AnalyticsCanonicalFixtures.D2_S3_SCAN);
    }

    @Test
    @DisplayName("7.6 broker replay converges payment-before-held with held-before-payment")
    void brokerReplayConvergesCrossTopicReorder() throws Exception {
        List<EventEnvelope<JsonNode>> s3 = AnalyticsCanonicalFixtures.s3History();

        publishAll(s3);
        await(Duration.ofSeconds(45), () -> processedMarkerCount() == s3.size());
        String first = snapshot();
        assertThat(first).isNotBlank();
        var metrics = sessionMetrics.findById(AnalyticsCanonicalFixtures.S3).orElseThrow();
        assertThat(metrics.getPaymentsSucceeded()).isOne();
        assertThat(metrics.getTicketsScanned()).isOne();

        clearReadModel();

        // Same logical S3 history, payment + scan evidence published before hold/confirm/issue:
        // records move only relative to other topic families.
        publishAll(List.of(s3.get(1), s3.get(0), s3.get(4), s3.get(2), s3.get(3)));
        await(Duration.ofSeconds(45), () -> processedMarkerCount() == s3.size());
        assertThat(snapshot()).isEqualTo(first);
        assertSessionWatermark(AnalyticsCanonicalFixtures.S3, AnalyticsCanonicalFixtures.D2_S3_SCAN);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void publishAll(List<EventEnvelope<JsonNode>> envelopes) throws Exception {
        for (EventEnvelope<JsonNode> envelope : envelopes) {
            kafkaTemplate.send(topicFor(envelope.eventType()), envelope.eventId(),
                    AnalyticsEnvelopeFactory.toJson(envelope)).get(10, TimeUnit.SECONDS);
        }
    }

    /** Topic routing mirrors the production consumer families (see AnalyticsSnapshot.Driver). */
    private static String topicFor(String eventType) {
        if (eventType.startsWith("Reservation")) {
            return EventTopics.RESERVATION_EVENTS;
        }
        if (eventType.startsWith("Payment")) {
            return EventTopics.PAYMENT_EVENTS;
        }
        if (eventType.startsWith("Ticket")) {
            return EventTopics.TICKET_EVENTS;
        }
        return EventTopics.EVENT_EVENTS;
    }

    private int processedMarkerCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events", Integer.class);
        return count == null ? 0 : count;
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

    /**
     * REV-005 regression pin: the session source watermark must converge to the maximum
     * correlated fact time (S3 scan at 11:00) in every delivery order — never stick at the
     * older issue time (10:10) when the scan is consumed before reservation correlation.
     */
    private void assertSessionWatermark(UUID eventSessionId, java.time.Instant expected) {
        var fact = sessionFacts.findById(eventSessionId).orElseThrow();
        assertThat(fact.getLastSourceEventAt()).isEqualTo(expected);
    }

    private void assertRetainedHistoryReadable(Set<String> topics) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "retention-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Set<String> seen = new HashSet<>();
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.copyOf(topics));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && !seen.containsAll(topics)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    seen.add(record.topic());
                }
            }
        }
        assertThat(seen).containsAll(topics);
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        if (!condition.getAsBoolean()) {
            throw new IllegalStateException("Timed out waiting for condition after " + timeout);
        }
    }
}
