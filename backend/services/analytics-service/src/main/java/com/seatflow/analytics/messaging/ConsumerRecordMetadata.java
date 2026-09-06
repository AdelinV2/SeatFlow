package com.seatflow.analytics.messaging;

/**
 * Minimal Kafka record identity carried into projection handlers.
 *
 * <p>Persisted onto the {@code processed_events} claim so operators can trace which
 * topic/partition/offset first owned an event. Never used as the deduplication key —
 * the key is always {@code EventEnvelope.eventId}.
 */
public record ConsumerRecordMetadata(
        String topic,
        int partition,
        long offset,
        String key
) {
}
