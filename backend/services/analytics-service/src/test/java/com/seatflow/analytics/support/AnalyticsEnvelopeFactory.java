package com.seatflow.analytics.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatflow.common.events.EventEnvelope;

import java.time.Instant;
import java.util.UUID;

/**
 * Deterministic {@link EventEnvelope} builders for TASK-P14-007 canonical fixtures.
 *
 * <p>Every builder takes an explicit {@code eventId} (the Kafka idempotency identity) and an
 * explicit {@code occurredAt} business-event time. Payload {@code occurredAt} mirrors the
 * envelope time so projection business time stays deterministic. Money uses major-unit decimal
 * strings (for example {@code "200.00"}) exactly as producers emit them; analytics converts to
 * minor units.
 *
 * <p>No random IDs or wall-clock times are generated here: callers pass fixed UUIDs from
 * {@link AnalyticsCanonicalFixtures}.
 *
 * <h2>Producer-binding status (REV-001, honest scope)</h2>
 * <p>Builders for {@code ReservationHeldEvent}, {@code ReservationConfirmedEvent},
 * {@code ReservationExpiredEvent}, {@code ReservationCancelledEvent}, {@code PaymentCompleted},
 * {@code PaymentFailed}, {@code TicketIssued}, and {@code EVENT_*} mirror implemented producer
 * outbox serialization (subset-compatible: every analytics-required field matches the real
 * producer record; producers may carry additional fields). Producer-to-analytics binding for
 * these families is proven by {@code AnalyticsProducerEnvelopeContractTest} plus the
 * producer-side field assertions in reservation/payment/ticket service tests.
 *
 * <p>Builders for {@code ReservationRefunded}, {@code PaymentRefunded}, {@code TicketRevoked},
 * and {@code TicketScanned}/{@code TicketValidated} are <em>projection-reducer coverage</em>,
 * not producer-bound contracts: no production service emits them yet (Phase 13
 * {@code 000-phase-overview.md} is {@code PLANNED}; ticket scanning is synchronous REST only in
 * {@code TicketServiceImpl.validateTicket}, which writes {@code USED} state plus a
 * {@code TicketValidation} audit row with no outbox event). They exercise the P14-003-owned
 * dispatcher allowlist + reducer semantics so the first real P13 producer contract binds
 * without a projection change. They must be rebound to real producer serialization (real event
 * records/outbox payload builders, not hand-shaped JSON) as soon as Phase 13 lands; until
 * then they must not be read as end-to-end refund/scan/revocation proof.
 */
public final class AnalyticsEnvelopeFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private AnalyticsEnvelopeFactory() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    // ------------------------------------------------------------------
    // Reservation family (topic seatflow.reservation.events)
    // ------------------------------------------------------------------

    public static EventEnvelope<JsonNode> held(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId,
            int seatCount, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        var seatIds = payload.putArray("seatIds");
        for (int i = 0; i < seatCount; i++) {
            seatIds.add(new UUID(0x5EEDL, i).toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationHeldEvent", at, payload);
    }

    public static EventEnvelope<JsonNode> confirmed(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId,
            UUID paymentId, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        payload.put("paymentId", paymentId.toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationConfirmedEvent", at, payload);
    }

    public static EventEnvelope<JsonNode> expired(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationExpiredEvent", at, payload);
    }

    public static EventEnvelope<JsonNode> cancelled(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationCancelledEvent", at, payload);
    }

    /**
     * Projection-reducer coverage for the future P13 {@code ReservationRefunded} contract
     * (see class javadoc: no producer emits this yet).
     */
    public static EventEnvelope<JsonNode> reservationRefunded(
            String eventId, UUID reservationId, UUID eventUuid, UUID sessionId, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        payload.put("eventSessionId", sessionId.toString());
        payload.put("eventId", eventUuid.toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "ReservationRefunded", at, payload);
    }

    // ------------------------------------------------------------------
    // Payment family (topic seatflow.payment.events)
    // ------------------------------------------------------------------

    public static EventEnvelope<JsonNode> completed(
            String eventId, UUID paymentId, UUID reservationId, UUID sessionId, UUID eventUuid,
            String majorAmount, String currency, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentId", paymentId.toString());
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("amount", majorAmount);
        payload.put("currency", currency);
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "PaymentCompleted", at, payload);
    }

    public static EventEnvelope<JsonNode> failed(
            String eventId, UUID paymentId, UUID reservationId, UUID sessionId, UUID eventUuid,
            Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentId", paymentId.toString());
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "PaymentFailed", at, payload);
    }

    /**
     * Projection-reducer coverage for the future P13 {@code PaymentRefunded} contract
     * (see class javadoc: no producer emits this yet).
     */
    public static EventEnvelope<JsonNode> paymentRefunded(
            String eventId, UUID paymentId, UUID reservationId, UUID sessionId, UUID eventUuid,
            String majorAmount, String currency, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("paymentId", paymentId.toString());
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("amount", majorAmount);
        payload.put("currency", currency);
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "PaymentRefunded", at, payload);
    }

    // ------------------------------------------------------------------
    // Ticket family (topic seatflow.ticket.events)
    // ------------------------------------------------------------------

    public static EventEnvelope<JsonNode> issued(
            String eventId, UUID ticketId, UUID reservationId, UUID sessionId, UUID eventUuid,
            Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("ticketId", ticketId.toString());
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "TicketIssued", at, payload);
    }

    /**
     * Projection-reducer coverage for the future P13 {@code TicketRevoked} contract
     * (see class javadoc: no producer emits this yet).
     */
    public static EventEnvelope<JsonNode> revokedForTicket(
            String eventId, UUID ticketId, UUID reservationId, UUID sessionId, UUID eventUuid,
            Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("ticketId", ticketId.toString());
        if (reservationId != null) {
            payload.put("reservationId", reservationId.toString());
        }
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "TicketRevoked", at, payload);
    }

    /**
     * Projection-reducer coverage for the future P13 reservation-scoped revocation evidence
     * (see class javadoc: no producer emits this yet).
     */
    public static EventEnvelope<JsonNode> revokedForReservation(
            String eventId, UUID reservationId, UUID sessionId, UUID eventUuid, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("reservationId", reservationId.toString());
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        if (eventUuid != null) {
            payload.put("eventId", eventUuid.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, "TicketRevoked", at, payload);
    }

    /**
     * Projection-reducer coverage for accepted-scan semantics (see class javadoc: scanning is
     * synchronous REST with no outbox event yet).
     */
    public static EventEnvelope<JsonNode> scanned(
            String eventId, UUID ticketId, UUID reservationId, UUID sessionId, Instant at) {
        return scannedAs(eventId, "TicketScanned", ticketId, reservationId, sessionId, at);
    }

    public static EventEnvelope<JsonNode> scannedAs(
            String eventId, String eventType, UUID ticketId, UUID reservationId, UUID sessionId,
            Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("ticketId", ticketId.toString());
        if (reservationId != null) {
            payload.put("reservationId", reservationId.toString());
        }
        if (sessionId != null) {
            payload.put("eventSessionId", sessionId.toString());
        }
        payload.put("occurredAt", at.toString());
        return envelope(eventId, eventType, at, payload);
    }

    // ------------------------------------------------------------------
    // Event lifecycle family (topic seatflow.event.events)
    // ------------------------------------------------------------------

    public static EventEnvelope<JsonNode> lifecycle(
            String eventId, String eventType, UUID eventUuid, Instant at) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("eventId", eventUuid.toString());
        payload.put("occurredAt", at.toString());
        return envelope(eventId, eventType, at, payload);
    }

    // ------------------------------------------------------------------
    // Raw envelope helpers (malformed/unknown envelope tests)
    // ------------------------------------------------------------------

    public static EventEnvelope<JsonNode> envelope(
            String eventId, String eventType, Instant at, JsonNode payload) {
        return new EventEnvelope<>(eventId, eventType, at,
                "corr-p14-007", null, "agg-p14-007", EventEnvelope.CURRENT_VERSION, payload);
    }

    /** Serialize one envelope to the canonical Kafka JSON record value. */
    public static String toJson(EventEnvelope<JsonNode> envelope) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("eventId", envelope.eventId());
        root.put("eventType", envelope.eventType());
        root.put("occurredAt", envelope.occurredAt().toString());
        root.put("aggregateId", envelope.aggregateId());
        root.put("correlationId", envelope.correlationId());
        root.put("version", envelope.version());
        root.set("payload", envelope.payload());
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize canonical envelope", ex);
        }
    }
}
