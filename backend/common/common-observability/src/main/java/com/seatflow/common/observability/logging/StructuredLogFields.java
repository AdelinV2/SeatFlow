package com.seatflow.common.observability.logging;

/**
 * Standard ECS/Logstash dotted field names for MDC context and structured logging.
 */
public final class StructuredLogFields {

    private StructuredLogFields() {
        // Utility class
    }

    public static final String TRACE_ID = "trace.id";
    public static final String SPAN_ID = "span.id";
    public static final String CORRELATION_ID = "correlation.id";
    public static final String USER_ID = "user.id";
    public static final String HTTP_METHOD = "http.method";
    public static final String HTTP_URI = "http.uri";
    public static final String HTTP_CLIENT_IP = "http.client_ip";
}
