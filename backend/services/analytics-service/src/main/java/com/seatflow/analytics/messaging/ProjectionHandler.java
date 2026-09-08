package com.seatflow.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.common.events.EventEnvelope;

import java.util.Set;

/**
 * Narrow deterministic projection seam for one cohesive analytics event family.
 *
 * <p>TASK-P14-002 owns the ingestion boundary (claim + dispatch + transaction). Business
 * fact/aggregate semantics belong to P14-003; handlers here validate their contract slice
 * and record receipt without cross-service REST/DB coupling, HTTP/email/Kafka side effects,
 * or source-database reads.
 *
 * <p>Implementations must be idempotent under redelivery on their own (the claim already
 * guarantees at-most-once invocation per {@code eventId}, but a crash between DB commit and
 * Kafka offset progression can still redeliver a committed event as a no-op claim).
 */
public interface ProjectionHandler {

    /**
     * Canonical {@code EventEnvelope.eventType} values this handler owns.
     */
    Set<String> eventTypes();

    /**
     * Project one already-claimed event. Runs inside the claim's local analytics transaction;
     * throwing rolls back both the projection mutations and the {@code processed_events} claim.
     */
    void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata);
}
