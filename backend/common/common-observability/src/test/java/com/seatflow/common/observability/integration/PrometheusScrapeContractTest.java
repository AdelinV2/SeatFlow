package com.seatflow.common.observability.integration;

import com.seatflow.common.observability.metrics.MetricTagPolicy;
import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusScrapeContractTest {

    private PrometheusMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // simulate common tags
        registry.config().commonTags("application", "seatflow-test", "environment", "test");
    }

    @Test
    void shouldExposeRequiredBusinessMetersWithCorrectTypes() {
        // Reservation meters
        Counter.builder(SeatFlowMetricNames.RESERVATIONS_CREATED)
                .tags(MetricTagPolicy.reservationCreated("SUCCESS"))
                .register(registry).increment();
        Counter.builder(SeatFlowMetricNames.RESERVATIONS_CONFLICTS)
                .tags(MetricTagPolicy.reservationConflict("ALREADY_HELD"))
                .register(registry).increment();
        Timer.builder(SeatFlowMetricNames.RESERVATIONS_HOLD_DURATION)
                .tags(MetricTagPolicy.holdDuration("SUCCESS"))
                .register(registry).record(Duration.ofMillis(150));
        Counter.builder(SeatFlowMetricNames.RESERVATIONS_EXPIRED)
                .tags(MetricTagPolicy.reservationExpired("EXPIRED"))
                .register(registry).increment();

        // Payment
        Counter.builder(SeatFlowMetricNames.PAYMENTS_PROCESSED)
                .tags(MetricTagPolicy.paymentProcessed("SUCCESS", "USD", "CARD"))
                .register(registry).increment();

        // Ticket
        Counter.builder(SeatFlowMetricNames.TICKETS_ISSUED)
                .tags(MetricTagPolicy.ticketIssued("PAYMENT_COMPLETED"))
                .register(registry).increment();

        // Outbox
        Timer.builder(SeatFlowMetricNames.OUTBOX_PUBLISH_LATENCY)
                .tags(MetricTagPolicy.outboxPublish("reservation-service", "ReservationHeldEvent", "SUCCESS"))
                .register(registry).record(Duration.ofMillis(50));
        Counter.builder(SeatFlowMetricNames.OUTBOX_RETRY_COUNT)
                .tags(MetricTagPolicy.outboxRetry("reservation-service", "ReservationHeldEvent"))
                .register(registry).increment();

        // Realtime gauge
        AtomicInteger connections = new AtomicInteger(5);
        Gauge.builder(SeatFlowMetricNames.WEBSOCKET_ACTIVE_CONNECTIONS, connections, AtomicInteger::get)
                .register(registry);

        String scrape = registry.scrape();

        // Required metrics present — verify via registry and scrape
        // Registry-level existence (Micrometer name)
        assertThat(registry.find(SeatFlowMetricNames.RESERVATIONS_CREATED).counter()).isNotNull();
        assertThat(registry.find(SeatFlowMetricNames.RESERVATIONS_CONFLICTS).counter()).isNotNull();
        assertThat(registry.find(SeatFlowMetricNames.RESERVATIONS_HOLD_DURATION).timer()).isNotNull();
        assertThat(registry.find(SeatFlowMetricNames.RESERVATIONS_EXPIRED).counter()).isNotNull();
        assertThat(registry.find(SeatFlowMetricNames.PAYMENTS_PROCESSED).counter()).isNotNull();
        assertThat(registry.find(SeatFlowMetricNames.TICKETS_ISSUED).counter()).isNotNull();
        assertThat(registry.find(SeatFlowMetricNames.OUTBOX_PUBLISH_LATENCY).timer()).isNotNull();
        assertThat(registry.find(SeatFlowMetricNames.OUTBOX_RETRY_COUNT).counter()).isNotNull();
        assertThat(registry.find(SeatFlowMetricNames.WEBSOCKET_ACTIVE_CONNECTIONS).gauge()).isNotNull();

        // Prometheus exposition names (with _total suffix for counters)
        assertThat(scrape).contains("seatflow_reservations_conflicts_total");
        assertThat(scrape).contains("seatflow_reservations_hold_duration");
        assertThat(scrape).contains("seatflow_reservations_expired_total");
        assertThat(scrape).contains("seatflow_payments_processed_total");
        assertThat(scrape).contains("seatflow_tickets_issued_total");
        assertThat(scrape).contains("seatflow_outbox_publish_latency");
        assertThat(scrape).contains("seatflow_outbox_retry_count_total");
        assertThat(scrape).contains("seatflow_websocket_active_connections");
        assertThat(scrape).contains("seatflow_reservations_created_events_total");
        assertThat(scrape).contains("status=\"SUCCESS\"");

        // Common tags present
        assertThat(scrape).contains("application=\"seatflow-test\"");
        assertThat(scrape).contains("environment=\"test\"");

        // Types present — check via scrape contains TYPE lines for at least one counter and gauge
        assertThat(scrape).contains("# TYPE seatflow_reservations_conflicts_total counter");
        assertThat(scrape).contains("# TYPE seatflow_reservations_hold_duration");
        assertThat(scrape).contains("# TYPE seatflow_websocket_active_connections gauge");
    }

    @Test
    void shouldNotExposeForbiddenHighCardinalityTags() {
        Counter.builder(SeatFlowMetricNames.RESERVATIONS_CREATED)
                .tags(MetricTagPolicy.reservationCreated("SUCCESS"))
                .register(registry).increment();
        Counter.builder(SeatFlowMetricNames.PAYMENTS_PROCESSED)
                .tags(MetricTagPolicy.paymentProcessed("SUCCESS", "USD", "CARD"))
                .register(registry).increment();

        String scrape = registry.scrape();

        // Forbidden tag keys must not appear
        for (String forbidden : new String[]{"userId", "reservationId", "paymentId", "ticketId", "seatId", "eventId", "stripePaymentIntentId", "user_id", "traceId"}) {
            // Prometheus labels are lowercase with underscores; check that forbidden substrings are absent as label names
            // We check for label name pattern: forbidden=
            assertThat(scrape).doesNotContain(forbidden + "=");
        }
        // Raw UUID should not appear
        assertThat(scrape).doesNotContain("123e4567-e89b");
        // Should not contain raw URLs
        assertThat(scrape).doesNotContain("http://");
        assertThat(scrape).doesNotContain("https://");
    }

    @Test
    void shouldEnforceBoundedTagValuesExposition() {
        // Attempt to create meter with bounded tags — should succeed
        Counter.builder(SeatFlowMetricNames.RESERVATIONS_CONFLICTS)
                .tags(MetricTagPolicy.reservationConflict("LIMIT_EXCEEDED"))
                .register(registry).increment();

        String scrape = registry.scrape();
        assertThat(scrape).contains("reason=\"LIMIT_EXCEEDED\"");
        assertThat(scrape).doesNotContain("RANDOM_REASON");
    }

    @Test
    void shouldExposeHistogramBucketsForHttpAndCustomTimers() {
        // http.server.requests is produced by Spring Boot; simulate a timer with histogram
        Timer.builder("http.server.requests")
                .tags("uri", "/api/reservations", "method", "POST", "status", "201")
                .publishPercentileHistogram(true)
                .register(registry).record(Duration.ofMillis(120));

        Timer.builder(SeatFlowMetricNames.RESERVATIONS_HOLD_DURATION)
                .tags(MetricTagPolicy.holdDuration("SUCCESS"))
                .publishPercentileHistogram(true)
                .register(registry).record(Duration.ofMillis(80));

        Timer.builder(SeatFlowMetricNames.OUTBOX_PUBLISH_LATENCY)
                .tags(MetricTagPolicy.outboxPublish("payment-service", "PaymentCompleted", "SUCCESS"))
                .publishPercentileHistogram(true)
                .register(registry).record(Duration.ofMillis(30));

        String scrape = registry.scrape();
        // histogram buckets should be present
        assertThat(scrape).contains("http_server_requests_seconds_bucket");
        assertThat(scrape).contains("seatflow_reservations_hold_duration_seconds_bucket");
        assertThat(scrape).contains("seatflow_outbox_publish_latency_seconds_bucket");
    }
}
