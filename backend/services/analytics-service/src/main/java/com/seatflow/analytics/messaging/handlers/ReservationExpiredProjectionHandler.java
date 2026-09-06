package com.seatflow.analytics.messaging.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionHandler;
import com.seatflow.analytics.projection.AnalyticsProjectionReconciler;
import com.seatflow.analytics.projection.ProjectionImpact;
import com.seatflow.analytics.projection.ProjectionPayloads;
import com.seatflow.analytics.projection.ReservationProjectionHandler;
import com.seatflow.common.events.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Reservation-expired projection with stale-replay protection (TASK-P14-003).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpiredProjectionHandler implements ProjectionHandler {

    private final ReservationProjectionHandler reservations;
    private final AnalyticsProjectionReconciler reconciler;

    @Override
    public Set<String> eventTypes() {
        return Set.of("ReservationExpiredEvent");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        UUID reservationId = ProjectionPayloads.uuid(payload, "reservationId");
        UUID eventId = ProjectionPayloads.uuid(payload, "eventId");
        UUID eventSessionId = ProjectionPayloads.uuid(payload, "eventSessionId");
        Instant occurredAt = ProjectionPayloads.factTime(envelope);
        ProjectionImpact impact =
                reservations.onExpired(reservationId, eventId, eventSessionId, occurredAt);
        reconciler.reconcile(impact, occurredAt);
        log.info("Projected ReservationExpiredEvent. eventId={} reservationId={} eventSessionId={}",
                envelope.eventId(), reservationId, eventSessionId);
    }
}
