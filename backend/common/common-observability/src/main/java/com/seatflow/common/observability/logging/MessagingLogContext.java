package com.seatflow.common.observability.logging;

import com.seatflow.common.observability.context.CorrelationContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

/**
 * Scopes MDC and correlation context around a Kafka listener invocation without
 * treating a domain event identifier as a distributed trace identifier.
 */
public final class MessagingLogContext implements AutoCloseable {

    private final Map<String, String> previousMdc;
    private final String previousCorrelationId;

    private MessagingLogContext(Map<String, String> previousMdc, String previousCorrelationId) {
        this.previousMdc = previousMdc;
        this.previousCorrelationId = previousCorrelationId;
    }

    public static MessagingLogContext open(String candidateCorrelationId, Tracer tracer) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        String previousCorrelationId = CorrelationContext.getCorrelationId().orElse(null);
        String correlationId = resolveCorrelationId(candidateCorrelationId);

        MDC.clear();
        CorrelationContext.setCorrelationId(correlationId);
        MDC.put(StructuredLogFields.CORRELATION_ID, correlationId);
        injectTraceContext(tracer);

        return new MessagingLogContext(previousMdc, previousCorrelationId);
    }

    @Override
    public void close() {
        if (previousMdc == null || previousMdc.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }

        CorrelationContext.clear();
        if (previousCorrelationId != null) {
            CorrelationContext.setCorrelationId(previousCorrelationId);
        }
    }

    private static String resolveCorrelationId(String candidate) {
        if (StringUtils.hasText(candidate)) {
            String trimmed = candidate.trim();
            try {
                UUID parsed = UUID.fromString(trimmed);
                if (parsed.toString().equalsIgnoreCase(trimmed)) {
                    return trimmed;
                }
            } catch (IllegalArgumentException ignored) {
                // Untrusted or malformed envelope context must not enter MDC.
            }
        }
        return UUID.randomUUID().toString();
    }

    private static void injectTraceContext(Tracer tracer) {
        if (tracer == null) {
            return;
        }

        Span span = tracer.currentSpan();
        TraceContext traceContext = span == null ? null : span.context();
        if (traceContext == null) {
            return;
        }

        if (isValidW3cHexId(traceContext.traceId(), 32)) {
            MDC.put(StructuredLogFields.TRACE_ID, traceContext.traceId());
        }
        if (isValidW3cHexId(traceContext.spanId(), 16)) {
            MDC.put(StructuredLogFields.SPAN_ID, traceContext.spanId());
        }
    }

    private static boolean isValidW3cHexId(String value, int expectedLength) {
        return StringUtils.hasText(value)
                && value.length() == expectedLength
                && !value.chars().allMatch(character -> character == '0')
                && value.chars().allMatch(character -> (character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f'));
    }
}
