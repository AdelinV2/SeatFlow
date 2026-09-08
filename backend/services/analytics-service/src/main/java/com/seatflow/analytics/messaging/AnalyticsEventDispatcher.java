package com.seatflow.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.common.events.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Safe envelope classification / deserialization / validation boundary.
 *
 * <h2>Contract audit (TASK-P14-002 gate, verified against implemented producer code)</h2>
 * <table>
 * <caption>Final producer class to envelope eventType to topic</caption>
 * <tr><th>Analytics semantic</th><th>Final producer eventType</th><th>Topic</th></tr>
 * <tr><td>Reservation held</td><td>{@code ReservationHeldEvent} (ReservationServiceImpl)</td><td>{@code seatflow.reservation.events}</td></tr>
 * <tr><td>Reservation confirmed</td><td>{@code ReservationConfirmedEvent}</td><td>{@code seatflow.reservation.events}</td></tr>
 * <tr><td>Reservation expired</td><td>{@code ReservationExpiredEvent}</td><td>{@code seatflow.reservation.events}</td></tr>
 * <tr><td>Reservation cancelled</td><td>{@code ReservationCancelledEvent}</td><td>{@code seatflow.reservation.events}</td></tr>
 * <tr><td>Payment success</td><td>{@code PaymentCompleted} (StripeWebhookServiceImpl, PaymentServiceImpl)</td><td>{@code seatflow.payment.events}</td></tr>
 * <tr><td>Payment failure</td><td>{@code PaymentFailed}</td><td>{@code seatflow.payment.events}</td></tr>
 * <tr><td>Ticket issue</td><td>{@code TicketIssued} (TicketServiceImpl)</td><td>{@code seatflow.ticket.events}</td></tr>
 * <tr><td>Event lifecycle</td><td>{@code EVENT_CREATED/PUBLISHED/CANCELLED/COMPLETED} (EventServiceImpl)</td><td>{@code seatflow.event.events}</td></tr>
 * </table>
 *
 * <p>Verified gaps (no producer changes made — analytics derives correlation from events):
 * <ul>
 *   <li>P13 refund/revocation has no implementation (only {@code 000-phase-overview.md}); no
 *       {@code PaymentRefunded}, {@code ReservationRefunded}, or ticket-revocation producer
 *       exists yet. TASK-P14-003 owns their projection semantics: the eventTypes are allowlisted
 *       with strict validation so tests and future producers converge on one contract instead of
 *       guessing at consumption time. They are ignored until produced, never guessed.</li>
 *   <li>Ticket scanning is synchronous REST only ({@code TicketServiceImpl.validateTicket} writes
 *       {@code TicketValidation} rows, no outbox). No {@code TicketScanned}/{@code TicketValidated}
 *       Kafka event exists; TASK-P14-003 allowlists both accepted-scan names with the same
 *       first-scan-wins projection so the first real producer contract binds without a
 *       dispatcher change.</li>
 *   <li>{@code EventSessionServiceImpl} publishes no Kafka events; there is no session-lifecycle
 *       topic event. Session correlation comes from the {@code eventSessionId} snapshot already
 *       present on reservation/payment/ticket payloads plus {@code EVENT_*} parent-event metadata.
 *       Capacity stays nullable — no trusted capacity snapshot exists in current payloads.</li>
 * </ul>
 *
 * <p>No additive producer field was required: every analytics correlation in the task matrix is
 * already present ({@code reservationId}, {@code paymentId}, {@code ticketId},
 * {@code eventSessionId}, {@code eventId}, minor-unit money + currency, {@code occurredAt}).
 * No second {@code ReservationCreated} event was introduced; {@code ReservationHeldEvent} remains
 * the canonical creation semantic. No PII is consumed beyond opaque correlation IDs.
 */
@Slf4j
@Component
public class AnalyticsEventDispatcher {

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    /**
     * Canonical analytics eventTypes: verified producer contracts plus P14-003-owned
     * refund/revocation/scan names (strictly validated; bound by tests until producers land).
     */
    public static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
            "ReservationHeldEvent",
            "ReservationConfirmedEvent",
            "ReservationExpiredEvent",
            "ReservationCancelledEvent",
            "ReservationRefunded",
            "PaymentCompleted",
            "PaymentFailed",
            "PaymentRefunded",
            "TicketIssued",
            "TicketRevoked",
            "TicketScanned",
            "TicketValidated",
            "EVENT_CREATED",
            "EVENT_PUBLISHED",
            "EVENT_CANCELLED",
            "EVENT_COMPLETED"
    );

    private final Map<String, ProjectionHandler> handlersByType;

    public AnalyticsEventDispatcher(List<ProjectionHandler> handlers) {
        Map<String, ProjectionHandler> registry = new HashMap<>();
        for (ProjectionHandler handler : handlers) {
            for (String eventType : handler.eventTypes()) {
                ProjectionHandler existing = registry.putIfAbsent(eventType, handler);
                if (existing != null) {
                    throw new IllegalStateException("Duplicate ProjectionHandler registration for eventType="
                            + eventType + ": " + existing.getClass().getName()
                            + " vs " + handler.getClass().getName());
                }
            }
        }
        this.handlersByType = Collections.unmodifiableMap(registry);
    }

    public boolean isSupported(String eventType) {
        return eventType != null && SUPPORTED_EVENT_TYPES.contains(eventType);
    }

    public Set<String> supportedEventTypes() {
        return SUPPORTED_EVENT_TYPES;
    }

    public Optional<ProjectionHandler> handlerFor(String eventType) {
        return Optional.ofNullable(handlersByType.get(eventType));
    }

    /**
     * Parse and strictly validate one raw envelope root.
     *
     * @throws AnalyticsEventValidationException when a <em>known</em> analytics event is malformed.
     *         Unknown event types are never passed here — callers must check
     *         {@link #isSupported(String)} first and ignore/ack them normally.
     */
    public EventEnvelope<JsonNode> parseAndValidate(JsonNode root, String topic) {
        if (root == null || root.isNull() || !root.isObject()) {
            throw new AnalyticsEventValidationException(null, null,
                    "Analytics envelope must be a JSON object");
        }
        String eventId = textOrNull(root, "eventId");
        String eventType = textOrNull(root, "eventType");
        String occurredAtRaw = textOrNull(root, "occurredAt");
        JsonNode payload = root.get("payload");

        if (eventId == null || eventId.isBlank()) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event requires non-blank eventId");
        }
        if (eventType == null || eventType.isBlank() || !SUPPORTED_EVENT_TYPES.contains(eventType)) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event requires allowlisted eventType, got: " + eventType);
        }
        Instant occurredAt = parseInstant(occurredAtRaw, eventId, eventType);
        if (payload == null || payload.isNull() || !payload.isObject()) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event requires non-null object payload");
        }
        validatePayload(eventType, eventId, payload);

        String aggregateId = textOrNull(root, "aggregateId");
        String correlationId = textOrNull(root, "correlationId");
        String causationId = textOrNull(root, "causationId");
        int version = root.path("version").asInt(EventEnvelope.CURRENT_VERSION);
        return new EventEnvelope<>(eventId, eventType, occurredAt, correlationId,
                causationId, aggregateId, version, payload);
    }

    private void validatePayload(String eventType, String eventId, JsonNode payload) {
        switch (eventType) {
            case "ReservationHeldEvent" -> {
                requireUuid(payload, "reservationId", eventId, eventType);
                requireUuid(payload, "eventSessionId", eventId, eventType);
                requireUuid(payload, "eventId", eventId, eventType);
                requireNonEmptyArray(payload, "seatIds", eventId, eventType);
            }
            case "ReservationConfirmedEvent" -> {
                requireUuid(payload, "reservationId", eventId, eventType);
                requireUuid(payload, "eventSessionId", eventId, eventType);
                requireUuid(payload, "eventId", eventId, eventType);
                requireUuid(payload, "paymentId", eventId, eventType);
            }
            case "ReservationExpiredEvent", "ReservationCancelledEvent", "ReservationRefunded" -> {
                requireUuid(payload, "reservationId", eventId, eventType);
                requireUuid(payload, "eventSessionId", eventId, eventType);
                requireUuid(payload, "eventId", eventId, eventType);
            }
            case "PaymentCompleted", "PaymentRefunded" -> {
                requireUuid(payload, "paymentId", eventId, eventType);
                requireUuid(payload, "reservationId", eventId, eventType);
                requireNonNegativeMoney(payload, "amount", eventId, eventType);
                requireCurrency(payload, eventId, eventType);
            }
            case "PaymentFailed" -> {
                requireUuid(payload, "paymentId", eventId, eventType);
                requireUuid(payload, "reservationId", eventId, eventType);
                optionalUuid(payload, "eventSessionId", eventId, eventType);
                optionalUuid(payload, "eventId", eventId, eventType);
            }
            case "TicketIssued" -> {
                requireUuid(payload, "ticketId", eventId, eventType);
                requireUuid(payload, "reservationId", eventId, eventType);
                optionalUuid(payload, "eventSessionId", eventId, eventType);
                optionalUuid(payload, "eventId", eventId, eventType);
            }
            case "TicketRevoked" -> {
                // Per-ticket (ticketId) or reservation-scoped (reservationId without ticket IDs);
                // at least one identity is required so the event is never silently dropped.
                String ticketId = payload.path("ticketId").asText(null);
                String reservationId = payload.path("reservationId").asText(null);
                boolean hasTicket = ticketId != null && !ticketId.isBlank();
                boolean hasReservation = reservationId != null && !reservationId.isBlank();
                if (!hasTicket && !hasReservation) {
                    throw new AnalyticsEventValidationException(eventId, eventType,
                            "Analytics event " + eventType + " requires ticketId or reservationId");
                }
                if (hasTicket) {
                    requireUuid(payload, "ticketId", eventId, eventType);
                }
                if (hasReservation) {
                    requireUuid(payload, "reservationId", eventId, eventType);
                }
                optionalUuid(payload, "eventSessionId", eventId, eventType);
                optionalUuid(payload, "eventId", eventId, eventType);
            }
            case "TicketScanned", "TicketValidated" -> {
                requireUuid(payload, "ticketId", eventId, eventType);
                optionalUuid(payload, "reservationId", eventId, eventType);
                optionalUuid(payload, "eventSessionId", eventId, eventType);
                optionalUuid(payload, "eventId", eventId, eventType);
            }
            case "EVENT_CREATED", "EVENT_PUBLISHED", "EVENT_CANCELLED", "EVENT_COMPLETED" -> {
                requireUuid(payload, "eventId", eventId, eventType);
            }
            default -> throw new AnalyticsEventValidationException(eventId, eventType,
                    "Unsupported analytics eventType: " + eventType);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String text = child.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private static Instant parseInstant(String raw, String eventId, String eventType) {
        if (raw == null || raw.isBlank()) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event requires non-null occurredAt");
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ex) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event has unparseable occurredAt: " + raw, ex);
        }
    }

    private static void requireUuid(JsonNode payload, String field, String eventId, String eventType) {        String value = payload.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " requires field: " + field);
        }
        try {
            java.util.UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " field " + field + " must be a UUID", ex);
        }
    }

    private static void optionalUuid(JsonNode payload, String field, String eventId, String eventType) {
        String value = payload.path(field).asText(null);
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            java.util.UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " field " + field + " must be a UUID", ex);
        }
    }

    private static void requireNonEmptyArray(JsonNode payload, String field, String eventId, String eventType) {
        JsonNode array = payload.get(field);
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " requires non-empty array: " + field);
        }
    }

    private static void requireNonNegativeMoney(JsonNode payload, String field, String eventId, String eventType) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " requires money field: " + field);
        }
        final BigDecimal amount;
        try {
            amount = new BigDecimal(node.asText());
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " field " + field + " must be numeric", ex);
        }
        if (amount.signum() < 0) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " field " + field + " must be >= 0");
        }
    }

    private static void requireCurrency(JsonNode payload, String eventId, String eventType) {
        String currency = payload.path("currency").asText(null);
        if (currency == null || !CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new AnalyticsEventValidationException(eventId, eventType,
                    "Analytics event " + eventType + " requires 3-letter currency");
        }
    }
}
