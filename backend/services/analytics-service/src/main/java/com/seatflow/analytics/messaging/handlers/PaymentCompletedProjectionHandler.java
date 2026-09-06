package com.seatflow.analytics.messaging.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionHandler;
import com.seatflow.analytics.projection.AnalyticsProjectionReconciler;
import com.seatflow.analytics.projection.MoneyMinor;
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
 * Payment-completed projection: exact minor-unit gross revenue in its currency (TASK-P14-003).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedProjectionHandler implements ProjectionHandler {

    private final PaymentProjectionHandler payments;
    private final AnalyticsProjectionReconciler reconciler;

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentCompleted");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        UUID paymentId = ProjectionPayloads.uuid(payload, "paymentId");
        UUID reservationId = ProjectionPayloads.uuid(payload, "reservationId");
        UUID eventSessionId = ProjectionPayloads.optionalUuid(payload, "eventSessionId");
        UUID eventId = ProjectionPayloads.optionalUuid(payload, "eventId");
        String currency = ProjectionPayloads.text(payload, "currency");
        long amountMinor = MoneyMinor.toMinor(
                ProjectionPayloads.text(payload, "amount"),
                envelope.eventId(), envelope.eventType(), "amount");
        Instant occurredAt = ProjectionPayloads.factTime(envelope);
        ProjectionImpact impact = payments.onCompleted(envelope.eventType(), envelope.eventId(),
                paymentId, reservationId, eventSessionId, eventId, amountMinor, currency, occurredAt);
        reconciler.reconcile(impact, occurredAt);
        log.info("Projected PaymentCompleted. eventId={} paymentId={} reservationId={} currency={} amountMinor={}",
                envelope.eventId(), paymentId, reservationId, currency, amountMinor);
    }
}
