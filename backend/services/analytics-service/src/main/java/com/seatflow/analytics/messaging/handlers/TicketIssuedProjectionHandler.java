package com.seatflow.analytics.messaging.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionHandler;
import com.seatflow.common.events.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
public class TicketIssuedProjectionHandler implements ProjectionHandler {

    @Override
    public Set<String> eventTypes() {
        return Set.of("TicketIssued");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        log.info("Projecting TicketIssued. eventId={} ticketId={} reservationId={} eventSessionId={} topic={}",
                envelope.eventId(),
                payload.path("ticketId").asText(null),
                payload.path("reservationId").asText(null),
                payload.path("eventSessionId").asText(null),
                metadata.topic());
    }
}
