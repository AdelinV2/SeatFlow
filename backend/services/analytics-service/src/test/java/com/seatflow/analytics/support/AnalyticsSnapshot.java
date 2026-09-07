package com.seatflow.analytics.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.ConsumerRecordMetadata;
import com.seatflow.analytics.messaging.ProjectionEventProcessor;
import com.seatflow.common.events.EventEnvelope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared TASK-P14-007 projection driver + normalized snapshot helpers.
 *
 * <p>Snapshots exclude generated processing timestamps ({@code updated_at} /
 * {@code processed_at}) but include every business fact and aggregate column, so rebuild and
 * redelivery equality is meaningful. Topic routing mirrors the production consumer families.
 */
public final class AnalyticsSnapshot {

    private AnalyticsSnapshot() {
    }

    public static final class Driver {
        private final AtomicLong offsets = new AtomicLong();
        private final ProjectionEventProcessor processor;

        public Driver(ProjectionEventProcessor processor) {
            this.processor = processor;
        }

        public ProjectionEventProcessor.Outcome process(EventEnvelope<JsonNode> envelope) {
            String topic = switch (envelope.eventType()) {
                case String s when s.startsWith("Reservation") -> "seatflow.reservation.events";
                case String s when s.startsWith("Payment") -> "seatflow.payment.events";
                case String s when s.startsWith("Ticket") -> "seatflow.ticket.events";
                default -> "seatflow.event.events";
            };
            return processor.process(envelope,
                    new ConsumerRecordMetadata(topic, 0, offsets.getAndIncrement(), "p14-007"));
        }

        public void processAll(List<EventEnvelope<JsonNode>> envelopes) {
            envelopes.forEach(this::process);
        }
    }

    public static String snapshot(
            com.seatflow.analytics.repository.AnalyticsReservationFactRepository reservationFacts,
            com.seatflow.analytics.repository.AnalyticsPaymentFactRepository paymentFacts,
            com.seatflow.analytics.repository.AnalyticsTicketFactRepository ticketFacts,
            com.seatflow.analytics.repository.AnalyticsTicketRevocationFactRepository revocationFacts,
            com.seatflow.analytics.repository.AnalyticsSessionFactRepository sessionFacts,
            com.seatflow.analytics.repository.EventSessionMetricRepository sessionMetrics,
            com.seatflow.analytics.repository.EventSessionRevenueMetricRepository sessionRevenue,
            com.seatflow.analytics.repository.DailyOperationalMetricRepository dailyOperational,
            com.seatflow.analytics.repository.DailyRevenueMetricRepository dailyRevenue) {
        List<String> lines = new ArrayList<>();
        reservationFacts.findAll().stream()
                .sorted(Comparator.comparing(r -> r.getReservationId().toString()))
                .forEach(r -> lines.add("RES|" + r.getReservationId() + "|" + r.getEventId() + "|"
                        + r.getEventSessionId() + "|" + r.getCreatedAt() + "|" + r.getConfirmedAt()
                        + "|" + r.getExpiredAt() + "|" + r.getRefundedAt() + "|" + r.getCancelledAt()
                        + "|" + r.getSeatCount() + "|" + r.getCurrency() + "|" + r.getQuotedTotalMinor()
                        + "|" + r.getLastSourceEventAt()));
        paymentFacts.findAll().stream()
                .sorted(Comparator.comparing(p -> p.getPaymentId().toString()))
                .forEach(p -> lines.add("PAY|" + p.getPaymentId() + "|" + p.getReservationId() + "|"
                        + p.getEventSessionId() + "|" + p.getLatestStatus() + "|" + p.getCurrency()
                        + "|" + p.getCompletedAmountMinor() + "|" + p.getRefundedAmountMinor() + "|"
                        + p.getCompletedAt() + "|" + p.getFailedAt() + "|" + p.getRefundedAt()
                        + "|" + p.getLastSourceEventAt()));
        ticketFacts.findAll().stream()
                .sorted(Comparator.comparing(t -> t.getTicketId().toString()))
                .forEach(t -> lines.add("TIX|" + t.getTicketId() + "|" + t.getReservationId() + "|"
                        + t.getEventSessionId() + "|" + t.getIssuedAt() + "|" + t.getRevokedAt()
                        + "|" + t.getFirstScannedAt() + "|" + t.getStatus()
                        + "|" + t.getLastSourceEventAt()));
        revocationFacts.findAll().stream()
                .sorted(Comparator.comparing(r -> r.getReservationId().toString()))
                .forEach(r -> lines.add("BREV|" + r.getReservationId() + "|" + r.getEventSessionId()
                        + "|" + r.getRevokedAt() + "|" + r.getLastSourceEventAt()));
        sessionFacts.findAll().stream()
                .sorted(Comparator.comparing(s -> s.getEventSessionId().toString()))
                .forEach(s -> lines.add("SES|" + s.getEventSessionId() + "|" + s.getEventId() + "|"
                        + s.getCapacitySnapshot() + "|" + s.getLastSourceEventAt()));
        sessionMetrics.findAll().stream()
                .sorted(Comparator.comparing(m -> m.getEventSessionId().toString()))
                .forEach(m -> lines.add("SM|" + m.getEventSessionId() + "|" + m.getEventId() + "|"
                        + m.getCapacitySnapshot() + "|" + m.getReservationsCreated() + "|"
                        + m.getReservationsConfirmed() + "|" + m.getReservationsExpired() + "|"
                        + m.getPaymentsSucceeded() + "|" + m.getPaymentsWithFailure() + "|"
                        + m.getRefundsCompleted() + "|" + m.getTicketsIssued() + "|"
                        + m.getTicketsRevoked() + "|" + m.getTicketsScanned() + "|"
                        + m.getLastProjectedEventAt()));
        sessionRevenue.findAll().stream()
                .sorted(Comparator.comparing(m -> m.getEventSessionId() + "|" + m.getCurrency()))
                .forEach(m -> lines.add("SR|" + m.getEventSessionId() + "|" + m.getEventId() + "|"
                        + m.getCurrency() + "|" + m.getPaymentsSucceeded() + "|"
                        + m.getGrossRevenueMinor() + "|" + m.getRefundsCompleted() + "|"
                        + m.getRefundedRevenueMinor() + "|" + m.getLastProjectedEventAt()));
        dailyOperational.findAll().stream()
                .sorted(Comparator.comparing(m -> m.getMetricDate() + "|" + m.getEventSessionId().toString()))
                .forEach(m -> lines.add("DO|" + m.getMetricDate() + "|" + m.getEventId() + "|"
                        + m.getEventSessionId() + "|" + m.getReservationsCreated() + "|"
                        + m.getReservationsConfirmed() + "|" + m.getReservationsExpired() + "|"
                        + m.getPaymentsSucceeded() + "|" + m.getPaymentsWithFailure() + "|"
                        + m.getRefundsCompleted() + "|" + m.getTicketsIssued() + "|"
                        + m.getTicketsRevoked() + "|" + m.getTicketsScanned()));
        dailyRevenue.findAll().stream()
                .sorted(Comparator.comparing(
                        m -> m.getMetricDate() + "|" + m.getEventSessionId().toString() + "|" + m.getCurrency()))
                .forEach(m -> lines.add("DR|" + m.getMetricDate() + "|" + m.getEventId() + "|"
                        + m.getEventSessionId() + "|" + m.getCurrency() + "|"
                        + m.getPaymentsSucceeded() + "|" + m.getGrossRevenueMinor() + "|"
                        + m.getRefundsCompleted() + "|" + m.getRefundedRevenueMinor()));
        return String.join("\n", lines);
    }
}
