package com.seatflow.analytics.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsConsumerMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AnalyticsConsumerMetrics metrics = new AnalyticsConsumerMetrics(registry);

    @Test
    @DisplayName("Required consumer counters exist with bounded event_type labels")
    void requiredCountersExist() {
        metrics.incrementProcessed("PaymentCompleted");
        metrics.incrementDuplicate("PaymentCompleted");
        metrics.incrementIgnored("UserRegistered");
        metrics.incrementFailed("PaymentCompleted", "TRANSIENT");
        metrics.incrementDeadLettered("PaymentCompleted", "VALIDATION");

        assertThat(registry.find("seatflow.analytics.events.processed")
                .tags("event_type", "PaymentCompleted").counter()).isNotNull();
        assertThat(registry.find("seatflow.analytics.events.duplicate")
                .tags("event_type", "PaymentCompleted").counter()).isNotNull();
        assertThat(registry.find("seatflow.analytics.events.ignored")
                .tags("event_type", "UNKNOWN").counter()).isNotNull();
        assertThat(registry.find("seatflow.analytics.events.failed")
                .tags("event_type", "PaymentCompleted", "reason_category", "TRANSIENT").counter()).isNotNull();
        assertThat(registry.find("seatflow.analytics.events.dead_lettered")
                .tags("event_type", "PaymentCompleted", "reason_category", "VALIDATION").counter()).isNotNull();
    }

    @Test
    @DisplayName("High-cardinality identifiers never become metric labels")
    void identifiersNeverBecomeLabels() {
        String reservationId = UUID.randomUUID().toString();
        metrics.incrementProcessed(reservationId);
        metrics.incrementFailed("PaymentCompleted", "reservation " + reservationId + " exploded");
        metrics.incrementIgnored(null);

        assertThat(registry.find("seatflow.analytics.events.processed")
                .tags("event_type", "UNKNOWN").counter()).isNotNull();
        assertThat(registry.find("seatflow.analytics.events.processed")
                .tags("event_type", reservationId).counter()).isNull();
        assertThat(registry.find("seatflow.analytics.events.failed")
                .tags("event_type", "PaymentCompleted", "reason_category", "UNKNOWN").counter()).isNotNull();
        assertThat(registry.find("seatflow.analytics.events.ignored")
                .tags("event_type", "UNKNOWN").counter()).isNotNull();

        registry.getMeters().forEach(meter ->
                meter.getId().getTags().forEach(tag ->
                        assertThat(tag.getValue()).doesNotContain(reservationId)));
    }

    @Test
    @DisplayName("Counters accumulate per bounded label set")
    void countersAccumulate() {
        metrics.incrementProcessed("TicketIssued");
        metrics.incrementProcessed("TicketIssued");
        metrics.incrementDuplicate("TicketIssued");

        assertThat(registry.counter("seatflow.analytics.events.processed", "event_type", "TicketIssued").count())
                .isEqualTo(2.0);
        assertThat(registry.counter("seatflow.analytics.events.duplicate", "event_type", "TicketIssued").count())
                .isEqualTo(1.0);
    }
}
