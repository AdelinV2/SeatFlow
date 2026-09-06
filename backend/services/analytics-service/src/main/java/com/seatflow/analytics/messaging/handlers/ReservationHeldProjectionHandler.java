package com.seatflow.analytics.messaging.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionHandler;
import com.seatflow.common.events.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * P14-003-ready reservation-event projection seam.
 *
 * <p>TASK-P14-002 establishes the ingestion boundary only: the idempotency claim is already
 * held in the caller's transaction when this handler runs. Fact/aggregate writes arrive in
 * P14-003; until then the handler records receipt without mutating analytics facts so the
 * exactly-once claim mechanics stay independently verifiable.
 */
@Slf4j
@Component
public class ReservationHeldProjectionHandler implements ProjectionHandler {

    @Override
    public Set<String> eventTypes() {
        return Set.of("ReservationHeldEvent");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        log.info("Projecting ReservationHeldEvent. eventId={} reservationId={} eventSessionId={} seatCount={} topic={}",
                envelope.eventId(),
                payload.path("reservationId").asText(null),
                payload.path("eventSessionId").asText(null),
                payload.path("seatIds").size(),
                metadata.topic());
    }
}
