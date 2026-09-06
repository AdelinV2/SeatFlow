package com.seatflow.analytics.metrics;

import com.seatflow.common.observability.metrics.MetricTagPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Low-cardinality Kafka ingestion metrics for the analytics consumer boundary.
 *
 * <p>Label discipline is load-bearing: {@code event_type} is allowlisted (unknown values map to
 * {@code UNKNOWN}) and {@code reason_category} is a tiny enum. Event, reservation, payment,
 * ticket, and user IDs — and exception text — are never used as labels; they belong in
 * structured logs/traces.
 */
@Component
@RequiredArgsConstructor
public class AnalyticsConsumerMetrics {

    static final Set<String> ALLOWED_REASONS = Set.of("VALIDATION", "TRANSIENT", "MAPPING", "UNKNOWN");

    private final MeterRegistry meterRegistry;

    public void incrementProcessed(String eventType) {
        counter("seatflow.analytics.events.processed", boundedEventType(eventType)).increment();
    }

    public void incrementDuplicate(String eventType) {
        counter("seatflow.analytics.events.duplicate", boundedEventType(eventType)).increment();
    }

    public void incrementIgnored(String eventType) {
        counter("seatflow.analytics.events.ignored", boundedEventType(eventType)).increment();
    }

    public void incrementFailed(String eventType, String reasonCategory) {
        meterRegistry.counter("seatflow.analytics.events.failed",
                "event_type", boundedEventType(eventType),
                "reason_category", boundedReason(reasonCategory)).increment();
    }

    public void incrementDeadLettered(String eventType, String reasonCategory) {
        meterRegistry.counter("seatflow.analytics.events.dead_lettered",
                "event_type", boundedEventType(eventType),
                "reason_category", boundedReason(reasonCategory)).increment();
    }

    private io.micrometer.core.instrument.Counter counter(String name, String eventType) {
        Tags tags = Tags.of("event_type", eventType);
        MetricTagPolicy.validateTags(tags);
        return meterRegistry.counter(name, tags);
    }

    static String boundedEventType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = raw.trim();
        if (com.seatflow.analytics.messaging.AnalyticsEventDispatcher.SUPPORTED_EVENT_TYPES.contains(trimmed)) {
            return trimmed;
        }
        return "UNKNOWN";
    }

    static String boundedReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return ALLOWED_REASONS.contains(upper) ? upper : "UNKNOWN";
    }
}
