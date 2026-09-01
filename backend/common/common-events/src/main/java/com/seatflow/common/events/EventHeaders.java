package com.seatflow.common.events;

public final class EventHeaders {
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String CAUSATION_ID = "X-Causation-Id";
    public static final String EVENT_ID = "X-Event-Id";
    public static final String EVENT_TYPE = "X-Event-Type";
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";

    private EventHeaders() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
