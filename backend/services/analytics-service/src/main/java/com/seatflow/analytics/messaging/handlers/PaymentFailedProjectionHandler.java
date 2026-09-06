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
public class PaymentFailedProjectionHandler implements ProjectionHandler {

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentFailed");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        log.info("Projecting PaymentFailed. eventId={} paymentId={} reservationId={} topic={}",
                envelope.eventId(),
                payload.path("paymentId").asText(null),
                payload.path("reservationId").asText(null),
                metadata.topic());
    }
}
