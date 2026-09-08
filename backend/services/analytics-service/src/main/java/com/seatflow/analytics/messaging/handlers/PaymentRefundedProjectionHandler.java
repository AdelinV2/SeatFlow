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
 * Payment-refunded projection (P13 canonical completed-refund semantic, TASK-P14-003).
 *
 * <p>Only completed refunds reach revenue aggregates; refund-first arrivals are provisional
 * until the completion event validates them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRefundedProjectionHandler implements ProjectionHandler {

    private final PaymentProjectionHandler payments;
    private final AnalyticsProjectionReconciler reconciler;

    @Override
    public Set<String> eventTypes() {
        return Set.of("PaymentRefunded");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        UUID paymentId = ProjectionPayloads.uuid(payload, "paymentId");
        UUID reservationId = ProjectionPayloads.uuid(payload, "reservationId");
        UUID eventSessionId = ProjectionPayloads.optionalUuid(payload, "eventSessionId");
        UUID eventId = ProjectionPayloads.optionalUuid(payload, "eventId");
        String currency = ProjectionPayloads.text(payload, "currency");
        long refundMinor = MoneyMinor.toMinor(
                ProjectionPayloads.text(payload, "amount"),
                envelope.eventId(), envelope.eventType(), "amount");
        Instant occurredAt = ProjectionPayloads.factTime(envelope);
        ProjectionImpact impact = payments.onRefunded(envelope.eventType(), envelope.eventId(),
                paymentId, reservationId, eventSessionId, eventId, refundMinor, currency, occurredAt);
        reconciler.reconcile(impact, occurredAt);
        log.info("Projected PaymentRefunded. eventId={} paymentId={} reservationId={} currency={} refundMinor={}",
                envelope.eventId(), paymentId, reservationId, currency, refundMinor);
    }
}
