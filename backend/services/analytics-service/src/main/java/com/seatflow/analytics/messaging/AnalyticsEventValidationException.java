package com.seatflow.analytics.messaging;

/**
 * A known analytics event whose envelope or payload is malformed.
 *
 * <p>Non-retryable by consumer policy: the record goes directly to the analytics DLQ and is
 * never marked processed. Unknown event types on a subscribed shared topic are not errors at
 * all — they are ignored and acknowledged normally without throwing this exception.
 */
public class AnalyticsEventValidationException extends RuntimeException {

    private final String eventId;
    private final String eventType;

    public AnalyticsEventValidationException(String eventId, String eventType, String message) {
        super(message);
        this.eventId = eventId;
        this.eventType = eventType;
    }

    public AnalyticsEventValidationException(String eventId, String eventType, String message, Throwable cause) {
        super(message, cause);
        this.eventId = eventId;
        this.eventType = eventType;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }
}
