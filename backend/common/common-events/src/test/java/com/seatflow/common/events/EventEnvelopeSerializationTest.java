package com.seatflow.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    record TestEvent(String id, String name) implements DomainEvent {}

    @Test
    void shouldSerializeAndDeserializeWithPayloadRetention() throws Exception {
        TestEvent payload = new TestEvent("evt-1", "ReservationHeld");
        EventEnvelope<TestEvent> envelope = EventEnvelope.of(
                "ReservationHeld",
                "agg-123",
                "corr-456",
                "causation-789",
                payload
        );

        String json = objectMapper.writeValueAsString(envelope);
        EventEnvelope<TestEvent> deserialized = objectMapper.readValue(
                json, new com.fasterxml.jackson.core.type.TypeReference<EventEnvelope<TestEvent>>() {});

        assertThat(deserialized.eventId()).isEqualTo(envelope.eventId());
        assertThat(deserialized.eventType()).isEqualTo("ReservationHeld");
        assertThat(deserialized.aggregateId()).isEqualTo("agg-123");
        assertThat(deserialized.correlationId()).isEqualTo("corr-456");
        assertThat(deserialized.causationId()).isEqualTo("causation-789");
        assertThat(deserialized.version()).isEqualTo(EventEnvelope.CURRENT_VERSION);
        assertThat(deserialized.payload()).isEqualTo(payload);
        assertThat(deserialized.payload().name()).isEqualTo("ReservationHeld");
    }

    @Test
    void shouldSerializeTimestampAsIso8601Utc() throws Exception {
        EventEnvelope<TestEvent> envelope = EventEnvelope.of(
                "ReservationHeld", "agg-123", "corr-456", new TestEvent("evt-1", "x"));

        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json).contains("\"occurredAt\"");
        assertThat(json).contains("T");
        assertThat(json).containsPattern("\"occurredAt\":\"\\d{4}-\\d{2}-\\d{2}T[^\"]*Z?\"");

        EventEnvelope<TestEvent> deserialized = objectMapper.readValue(
                json, new com.fasterxml.jackson.core.type.TypeReference<EventEnvelope<TestEvent>>() {});
        assertThat(deserialized.occurredAt()).isInstanceOf(Instant.class);
    }

    @Test
    void shouldPreserveNullOptionalFields() throws Exception {
        EventEnvelope<TestEvent> envelope = EventEnvelope.of(
                "ReservationHeld", "agg-123", "corr-456", new TestEvent("evt-1", "x"));

        assertThat(envelope.causationId()).isNull();

        String json = objectMapper.writeValueAsString(envelope);
        EventEnvelope<TestEvent> deserialized = objectMapper.readValue(
                json, new com.fasterxml.jackson.core.type.TypeReference<EventEnvelope<TestEvent>>() {});

        assertThat(deserialized.causationId()).isNull();
    }

    @Test
    void shouldGenerateUniqueEventIdsAndTimestamps() {
        EventEnvelope<TestEvent> a = EventEnvelope.of("T", "agg", "corr", new TestEvent("1", "x"));
        EventEnvelope<TestEvent> b = EventEnvelope.of("T", "agg", "corr", new TestEvent("1", "x"));

        assertThat(a.eventId()).isNotEqualTo(b.eventId());
        assertThat(a.occurredAt()).isNotNull();
    }
}
