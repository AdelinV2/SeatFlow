package com.seatflow.common.observability.logging;

/**
 * The request-context fields emitted by SeatFlow production logs.
 *
 * <p>These names intentionally use dotted ECS-compatible keys. Keep this list
 * limited to the fields that are part of the cross-service request contract.</p>
 */
public final class StructuredLogFields {

    public static final String TRACE_ID = "trace.id";
    public static final String SPAN_ID = "span.id";
    public static final String CORRELATION_ID = "correlation.id";
    public static final String USER_ID = "user.id";
    public static final String HTTP_METHOD = "http.method";
    public static final String HTTP_URI = "http.uri";
    public static final String HTTP_CLIENT_IP = "http.client_ip";

    private StructuredLogFields() {
        // Utility class.
    }
}
