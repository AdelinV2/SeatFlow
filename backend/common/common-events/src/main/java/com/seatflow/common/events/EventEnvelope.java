package com.seatflow.common.events;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        String eventId,
        String eventType,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String aggregateId,
        int version,
        T payload
) {
    public static final int CURRENT_VERSION = 1;

    public static <T extends DomainEvent> EventEnvelope<T> of(
            String eventType,
            String aggregateId,
            String correlationId,
            String causationId,
            T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                Instant.now(),
                correlationId,
                causationId,
                aggregateId,
                CURRENT_VERSION,
                payload
        );
    }

    public static <T extends DomainEvent> EventEnvelope<T> of(
            String eventType,
            String aggregateId,
            String correlationId,
            T payload) {
        return of(eventType, aggregateId, correlationId, null, payload);
    }
}
