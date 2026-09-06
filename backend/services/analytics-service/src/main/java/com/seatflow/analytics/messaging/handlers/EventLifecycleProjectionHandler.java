package com.seatflow.analytics.messaging.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionHandler;
import com.seatflow.common.events.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Parent event-lifecycle projection seam ({@code EVENT_CREATED/PUBLISHED/CANCELLED/COMPLETED}).
 *
 * <p>Carries display/audit metadata only. Capacity stays nullable in P14 because no trusted
 * capacity snapshot exists in the current {@code EventCreated} payload.
 */
@Slf4j
@Component
public class EventLifecycleProjectionHandler implements ProjectionHandler {

    @Override
    public Set<String> eventTypes() {
        return Set.of("EVENT_CREATED", "EVENT_PUBLISHED", "EVENT_CANCELLED", "EVENT_COMPLETED");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        log.info("Projecting event lifecycle. eventType={} eventId={} payloadEventId={} topic={}",
                envelope.eventType(),
                envelope.eventId(),
                payload.path("eventId").asText(null),
                metadata.topic());
    }
}
