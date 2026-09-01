package com.seatflow.common.observability.tracing;

import com.seatflow.common.events.EventHeaders;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.Map;

/**
 * Best-effort W3C trace-context propagation for EventEnvelope headers.
 * OpenTelemetry owns protocol parsing so this class does not drift from the W3C grammar.
 */
public final class W3cTraceContextPropagator {

    private static final Logger log = LoggerFactory.getLogger(W3cTraceContextPropagator.class);
    private static final int MAX_TRACEPARENT_LENGTH = 512;
    private static final int MAX_TRACESTATE_LENGTH = 512;

    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? Collections.emptyList() : carrier.keySet();
        }
    };

    private static final io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator W3C =
            io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance();

    /** Injects the current valid OTel context without touching application correlation headers. */
    public void inject(Map<String, String> headers) {
        if (headers == null) {
            return;
        }
        try {
            if (Span.fromContext(Context.current()).getSpanContext().isValid()) {
                W3C.inject(Context.current(), headers, SETTER);
            }
        } catch (RuntimeException ex) {
            log.debug("Failed to inject W3C trace context; continuing without propagation. reason={}",
                    ex.getClass().getSimpleName());
        }
    }

    /**
     * Extracts a valid remote parent. Malformed or oversized untrusted headers yield a new root context.
     * Header values are never logged.
     */
    public Context extract(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Context.root();
        }
        String traceparent = headers.get(EventHeaders.TRACEPARENT);
        if (!StringUtils.hasText(traceparent) || traceparent.length() > MAX_TRACEPARENT_LENGTH) {
            return Context.root();
        }
        if (hasForbiddenTraceparentVersion(traceparent)) {
            log.warn("Invalid W3C traceparent header received; creating new root trace. reason=version_ff");
            return Context.root();
        }

        Map<String, String> carrier = headers;
        String tracestate = headers.get(EventHeaders.TRACESTATE);
        if (tracestate != null && tracestate.length() > MAX_TRACESTATE_LENGTH) {
            carrier = new java.util.HashMap<>(headers);
            carrier.remove(EventHeaders.TRACESTATE);
        }

        try {
            Context extracted = W3C.extract(Context.root(), carrier, GETTER);
            SpanContext spanContext = Span.fromContext(extracted).getSpanContext();
            if (spanContext.isValid()) {
                return extracted;
            }
            log.warn("Invalid W3C traceparent header received; creating new root trace. reason=extraction_invalid");
        } catch (RuntimeException ex) {
            log.warn("Failed to extract W3C trace context; creating new root trace. reason={}",
                    ex.getClass().getSimpleName());
        }
        return Context.root();
    }

    private static boolean hasForbiddenTraceparentVersion(String traceparent) {
        int separator = traceparent.indexOf('-');
        return separator == 2 && "ff".equals(traceparent.substring(0, separator));
    }
}
