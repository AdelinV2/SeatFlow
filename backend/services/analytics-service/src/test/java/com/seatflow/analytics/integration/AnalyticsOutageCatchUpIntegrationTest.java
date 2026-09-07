package com.seatflow.analytics.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.config.KafkaConsumerConfig;
import com.seatflow.analytics.repository.EventSessionMetricRepository;
import com.seatflow.analytics.repository.EventSessionRevenueMetricRepository;
import com.seatflow.analytics.support.AnalyticsCanonicalFixtures;
import com.seatflow.analytics.support.AnalyticsEnvelopeFactory;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REV-003 analytics outage catch-up evidence (TASK-P14-007 §7.17 step 5).
 *
 * <p>The analytics listener containers are stopped (genuinely absent at runtime, asserted via
 * {@code isRunning}), the canonical retained history is published to the real broker while
 * analytics is down, absence of projection is observed, then the containers restart with
 * their defined group state and every retained event projects. The publish side never touches
 * analytics: nothing on the source flow requires the read model, and no record produced
 * during the outage is lost.
 *
 * <p>Companion source-side proof (steps 1-4, real reservation service over a real broker with
 * no analytics on the classpath) lives in reservation-service
 * {@code AnalyticsAbsenceBrokerIsolationTest}; static/config facts live in
 * {@code infra/scripts/verify-analytics-isolation.sh}.
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
class AnalyticsOutageCatchUpIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_p14_007_outage_test")
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
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventSessionMetricRepository sessionMetrics;

    @Autowired
    private EventSessionRevenueMetricRepository sessionRevenue;

    @BeforeEach
    void ensureListenerRunning() {
        listenerRegistry.getListenerContainers().forEach(container -> {
            if (!container.isRunning()) {
                container.start();
            }
        });
    }

    @Test
    @DisplayName("7.17 events retained during the analytics outage project after recovery")
    void retainedEventsProjectAfterOutageRecovery() throws Exception {
        // 1-2. Analytics genuinely absent: stop every listener container, then publish the
        // retained history to the real broker while nothing consumes it.
        listenerRegistry.getListenerContainers().forEach(container -> container.stop());
        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty();
        assertThat(listenerRegistry.getListenerContainers())
                .allMatch(container -> !container.isRunning());

        List<EventEnvelope<JsonNode>> retained = AnalyticsCanonicalFixtures.s3History();
        for (EventEnvelope<JsonNode> envelope : retained) {
            kafkaTemplate.send(topicFor(envelope.eventType()), envelope.eventId(),
                    AnalyticsEnvelopeFactory.toJson(envelope)).get(10, TimeUnit.SECONDS);
        }

        // 3-4. Bounded absence observation: with analytics down, nothing projects. (A short
        // grace for in-flight polls only; the positive proof below awaits real conditions.)
        Thread.sleep(3000);
        assertThat(processedMarkerCount()).isZero();

        // 5. Start analytics later: retained events must project asynchronously.
        listenerRegistry.getListenerContainers().forEach(container -> container.start());

        await(Duration.ofSeconds(60), () -> processedMarkerCount() == retained.size());

        var metrics = sessionMetrics.findById(AnalyticsCanonicalFixtures.S3).orElseThrow();
        assertThat(metrics.getReservationsCreated()).isOne();
        assertThat(metrics.getReservationsConfirmed()).isOne();
        assertThat(metrics.getPaymentsSucceeded()).isOne();
        assertThat(metrics.getTicketsIssued()).isOne();
        assertThat(metrics.getTicketsScanned()).isOne();
        var revenue = sessionRevenue.findByEventSessionId(AnalyticsCanonicalFixtures.S3);
        assertThat(revenue).hasSize(1);
        assertThat(revenue.getFirst().getCurrency()).isEqualTo("EUR");
        assertThat(revenue.getFirst().getGrossRevenueMinor())
                .isEqualTo(AnalyticsCanonicalFixtures.S3_GROSS_MINOR);
    }

    private int processedMarkerCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events", Integer.class);
        return count == null ? 0 : count;
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
