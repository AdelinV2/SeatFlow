package com.seatflow.analytics.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.messaging.AnalyticsEventValidationException;
import com.seatflow.common.events.EventEnvelope;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared envelope-payload extraction for projection adapters (TASK-P14-003).
 *
 * <p>Dispatch-time validation already guarantees required fields; these helpers resolve
 * business-event time (trusted payload {@code occurredAt} when present, else the envelope time)
 * and optional correlation IDs. Processing time is never substituted for business-event time.
 */
public final class ProjectionPayloads {

    private ProjectionPayloads() {
    }

    /** Business-event time: trusted payload {@code occurredAt} when parseable, else envelope time. */
    public static Instant factTime(EventEnvelope<JsonNode> envelope) {
        JsonNode payload = envelope.payload();
        if (payload != null && payload.has("occurredAt")) {
            String raw = payload.path("occurredAt").asText(null);
            if (raw != null && !raw.isBlank()) {
                try {
                    return Instant.parse(raw);
                } catch (RuntimeException ignored) {
                    // Fall through to envelope time; dispatcher already validated the envelope.
                }
            }
        }
        return envelope.occurredAt();
    }

    public static UUID uuid(JsonNode payload, String field) {
        String raw = payload.path(field).asText(null);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            // Non-retryable: malformed identity can never project correctly. The DLQ record
            // preserves the envelope, so the field name plus offending value suffice for triage.
            throw new AnalyticsEventValidationException(null, null,
                    "Analytics payload field " + field + " must be a UUID, got: " + raw, ex);
        }
    }

    public static UUID optionalUuid(JsonNode payload, String field) {
        String raw = payload.path(field).asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new AnalyticsEventValidationException(null, null,
                    "Analytics payload field " + field + " must be a UUID, got: " + raw, ex);
        }
    }

    public static int arraySize(JsonNode payload, String field) {
        JsonNode array = payload.path(field);
        return array.isArray() ? array.size() : 0;
    }

    public static String text(JsonNode payload, String field) {
        return payload.path(field).asText(null);
    }
}
