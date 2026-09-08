package com.seatflow.analytics.messaging.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionHandler;
import com.seatflow.analytics.projection.AnalyticsProjectionReconciler;
import com.seatflow.analytics.projection.EventSessionProjectionHandler;
import com.seatflow.analytics.projection.ProjectionImpact;
import com.seatflow.analytics.projection.ProjectionPayloads;
import com.seatflow.common.events.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Parent event-lifecycle projection seam ({@code EVENT_CREATED/PUBLISHED/CANCELLED/COMPLETED}).
 *
 * <p>Carries display/audit metadata only. Capacity stays nullable in P14 because no trusted
 * capacity snapshot exists in current {@code EventCreated} payloads (TASK-P14-003).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventLifecycleProjectionHandler implements ProjectionHandler {

    private final EventSessionProjectionHandler sessions;
    private final AnalyticsProjectionReconciler reconciler;

    @Override
    public Set<String> eventTypes() {
        return Set.of("EVENT_CREATED", "EVENT_PUBLISHED", "EVENT_CANCELLED", "EVENT_COMPLETED");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        Instant occurredAt = ProjectionPayloads.factTime(envelope);
        ProjectionImpact impact =
                sessions.onEventLifecycle(envelope.eventType(), envelope.eventId(), occurredAt);
        reconciler.reconcile(impact, occurredAt);
        log.info("Projected event lifecycle. eventType={} eventId={} payloadEventId={}",
                envelope.eventType(),
                envelope.eventId(),
                envelope.payload().path("eventId").asText(null));
    }
}
