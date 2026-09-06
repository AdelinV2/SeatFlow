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
public class PaymentCompletedProjectionHandler implements ProjectionHandler {

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentCompleted");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        log.info("Projecting PaymentCompleted. eventId={} paymentId={} reservationId={} currency={} amount={} topic={}",
                envelope.eventId(),
                payload.path("paymentId").asText(null),
                payload.path("reservationId").asText(null),
                payload.path("currency").asText(null),
                payload.path("amount").asText(null),
                metadata.topic());
    }
}
