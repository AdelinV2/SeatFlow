package com.seatflow.common.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record EventEnvelope<T>(
        String eventId,
        String eventType,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String aggregateId,
        int version,
        T payload,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, String> headers
) {
    public static final int CURRENT_VERSION = 1;

    public EventEnvelope {
        if (headers == null) {
            headers = Collections.emptyMap();
        } else {
            headers = Collections.unmodifiableMap(new HashMap<>(headers));
        }
    }

    public EventEnvelope(String eventId,
                         String eventType,
                         Instant occurredAt,
                         String correlationId,
                         String causationId,
                         String aggregateId,
                         int version,
                         T payload) {
        this(eventId, eventType, occurredAt, correlationId, causationId, aggregateId, version, payload, Collections.emptyMap());
    }

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
                payload,
                Collections.emptyMap()
        );
    }

    public static <T extends DomainEvent> EventEnvelope<T> of(
            String eventType,
            String aggregateId,
            String correlationId,
            T payload) {
        return of(eventType, aggregateId, correlationId, null, payload);
    }

    public EventEnvelope<T> withHeaders(Map<String, String> newHeaders) {
        Map<String, String> copy = newHeaders == null ? Collections.emptyMap() : new HashMap<>(newHeaders);
        return new EventEnvelope<>(
                eventId, eventType, occurredAt, correlationId, causationId, aggregateId, version, payload, copy);
    }

    public EventEnvelope<T> withHeader(String key, String value) {
        Map<String, String> copy = new HashMap<>(headers);
        copy.put(key, value);
        return new EventEnvelope<>(
                eventId, eventType, occurredAt, correlationId, causationId, aggregateId, version, payload, copy);
    }
}
