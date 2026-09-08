package com.seatflow.analytics.messaging.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionHandler;
import com.seatflow.analytics.projection.AnalyticsProjectionReconciler;
import com.seatflow.analytics.projection.ProjectionImpact;
import com.seatflow.analytics.projection.ProjectionPayloads;
import com.seatflow.analytics.projection.TicketProjectionHandler;
import com.seatflow.common.events.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Ticket-issued projection with batch-revocation reconciliation (TASK-P14-003).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketIssuedProjectionHandler implements ProjectionHandler {

    private final TicketProjectionHandler tickets;
    private final AnalyticsProjectionReconciler reconciler;

    @Override
    public Set<String> eventTypes() {
        return Set.of("TicketIssued");
    }

    @Override
    public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        JsonNode payload = envelope.payload();
        UUID ticketId = ProjectionPayloads.uuid(payload, "ticketId");
        UUID reservationId = ProjectionPayloads.optionalUuid(payload, "reservationId");
        UUID eventSessionId = ProjectionPayloads.optionalUuid(payload, "eventSessionId");
        UUID eventId = ProjectionPayloads.optionalUuid(payload, "eventId");
        Instant occurredAt = ProjectionPayloads.factTime(envelope);
        ProjectionImpact impact =
                tickets.onIssued(ticketId, reservationId, eventSessionId, eventId, occurredAt);
        reconciler.reconcile(impact, occurredAt);
        log.info("Projected TicketIssued. eventId={} ticketId={} reservationId={} eventSessionId={}",
                envelope.eventId(), ticketId, reservationId, eventSessionId);
    }
}
