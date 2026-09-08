package com.seatflow.analytics.messaging.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionHandler;
import com.seatflow.analytics.projection.AnalyticsProjectionReconciler;
import com.seatflow.analytics.projection.PaymentProjectionHandler;
import com.seatflow.analytics.projection.ProjectionImpact;
import com.seatflow.analytics.projection.ProjectionPayloads;
import com.seatflow.common.events.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Payment-failed projection: failure evidence preserved independently of later success
 * (TASK-P14-003). Failure alone never reduces gross revenue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailedProjectionHandler implements ProjectionHandler {

    private final PaymentProjectionHandler payments;
    private final AnalyticsProjectionReconciler reconciler;

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentFailed");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        UUID paymentId = ProjectionPayloads.uuid(payload, "paymentId");
        UUID reservationId = ProjectionPayloads.uuid(payload, "reservationId");
        UUID eventSessionId = ProjectionPayloads.optionalUuid(payload, "eventSessionId");
        UUID eventId = ProjectionPayloads.optionalUuid(payload, "eventId");
        Instant occurredAt = ProjectionPayloads.factTime(envelope);
        ProjectionImpact impact =
                payments.onFailed(paymentId, reservationId, eventSessionId, eventId, occurredAt);
        reconciler.reconcile(impact, occurredAt);
        log.info("Projected PaymentFailed. eventId={} paymentId={} reservationId={}",
                envelope.eventId(), paymentId, reservationId);
    }
}
