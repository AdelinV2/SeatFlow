package com.seatflow.common.observability.tracing;

import com.seatflow.common.events.EventHeaders;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class W3cTraceContextPropagatorTest {

    private W3cTraceContextPropagator propagator;

    @BeforeEach
    void setUp() {
        propagator = new W3cTraceContextPropagator();
    }

    @AfterEach
    void tearDown() {
        // Ensure no leaked context
        assertThat(Context.current()).isEqualTo(Context.root());
    }

    @Test
    void shouldInjectValidTraceParentAndTracestate() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentId = "00f067aa0ba902b7";
        SpanContext sc = SpanContext.create(traceId, parentId, TraceFlags.getSampled(),
                TraceState.builder().put("rojo", "00f067aa0ba902b7").build());
        Span span = Span.wrap(sc);
        Context ctx = Context.current().with(span);
        try (Scope ignored = ctx.makeCurrent()) {
            Map<String, String> headers = new HashMap<>();
            headers.put(EventHeaders.CORRELATION_ID, "corr-123");
            headers.put("existing", "keep");
            propagator.inject(headers);
            assertThat(headers).containsEntry(EventHeaders.TRACEPARENT, "00-" + traceId + "-" + parentId + "-01");
            assertThat(headers).containsEntry(EventHeaders.CORRELATION_ID, "corr-123");
            assertThat(headers).containsKey(EventHeaders.TRACESTATE);
            assertThat(headers.get(EventHeaders.TRACESTATE)).contains("rojo=00f067aa0ba902b7");
        }
    }

    @Test
    void shouldInjectWithoutOverwritingCorrelationId() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentId = "00f067aa0ba902b7";
        SpanContext sc = SpanContext.create(traceId, parentId, TraceFlags.getSampled(), TraceState.getDefault());
        Span span = Span.wrap(sc);
        try (Scope ignored = Context.current().with(span).makeCurrent()) {
            Map<String, String> headers = new HashMap<>();
            headers.put(EventHeaders.CORRELATION_ID, "existing-corr");
            propagator.inject(headers);
            assertThat(headers.get(EventHeaders.CORRELATION_ID)).isEqualTo("existing-corr");
            assertThat(headers.get(EventHeaders.TRACEPARENT)).isEqualTo("00-" + traceId + "-" + parentId + "-01");
        }
    }

    @Test
    void shouldNotInjectWhenNoValidSpan() {
        Map<String, String> headers = new HashMap<>();
        propagator.inject(headers);
        assertThat(headers).doesNotContainKey(EventHeaders.TRACEPARENT);
    }

    @Test
    void shouldInjectNullHeadersSafely() {
        propagator.inject(null);
        // no exception
    }

    @Test
    void shouldExtractValidParentContext() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentId = "00f067aa0ba902b7";
        String traceparent = "00-" + traceId + "-" + parentId + "-01";
        Map<String, String> headers = new HashMap<>();
        headers.put(EventHeaders.TRACEPARENT, traceparent);
        headers.put(EventHeaders.TRACESTATE, "rojo=00f067aa0ba902b7");

        Context extracted = propagator.extract(headers);
        SpanContext sc = Span.fromContext(extracted).getSpanContext();
        assertThat(sc.isValid()).isTrue();
        assertThat(sc.getTraceId()).isEqualTo(traceId);
        assertThat(sc.getSpanId()).isEqualTo(parentId);
        assertThat(sc.isSampled()).isTrue();
        assertThat(sc.getTraceState().get("rojo")).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void shouldReturnRootForMalformedTraceParent() {
        String[] malformed = {
                null,
                "",
                "invalid",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01", // all zeros traceId
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", // all zeros parent
                "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01", // short traceId
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b-01", // short parentId
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01", // uppercase
                "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // ff version
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-zz", // invalid flags
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7", // missing flags
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra" // extra part
        };

        for (String value : malformed) {
            Map<String, String> headers = new HashMap<>();
            if (value != null) {
                headers.put(EventHeaders.TRACEPARENT, value);
            }
            Context extracted = propagator.extract(headers);
            assertThat(extracted).as("malformed value '%s' should return root", value).isEqualTo(Context.root());
            SpanContext sc = Span.fromContext(extracted).getSpanContext();
            assertThat(sc.isValid()).as("malformed value '%s' should yield invalid span", value).isFalse();
        }
    }

    @Test
    void shouldReturnRootWhenHeadersNullOrMissing() {
        assertThat(propagator.extract(null)).isEqualTo(Context.root());
        assertThat(propagator.extract(Collections.emptyMap())).isEqualTo(Context.root());
        Map<String, String> headers = Map.of("other", "value");
        assertThat(propagator.extract(headers)).isEqualTo(Context.root());
    }

    @Test
    void shouldIgnoreInvalidTracestateButAcceptTraceParent() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentId = "00f067aa0ba902b7";
        String traceparent = "00-" + traceId + "-" + parentId + "-01";
        Map<String, String> headers = new HashMap<>();
        headers.put(EventHeaders.TRACEPARENT, traceparent);
        headers.put(EventHeaders.TRACESTATE, "invalid,,tracestate"); // malformed
        Context extracted = propagator.extract(headers);
        SpanContext sc = Span.fromContext(extracted).getSpanContext();
        assertThat(sc.isValid()).isTrue();
        assertThat(sc.getTraceId()).isEqualTo(traceId);
        // tracestate should be empty or default when invalid
        assertThat(sc.getTraceState().isEmpty()).isTrue();
    }

    @Test
    void shouldUseExactLowercaseHeaderNames() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentId = "00f067aa0ba902b7";
        String traceparent = "00-" + traceId + "-" + parentId + "-01";
        Map<String, String> headersUpper = new HashMap<>();
        headersUpper.put("Traceparent", traceparent); // uppercase T
        Context extractedUpper = propagator.extract(headersUpper);
        assertThat(extractedUpper).isEqualTo(Context.root());

        Map<String, String> headersLower = new HashMap<>();
        headersLower.put("traceparent", traceparent);
        Context extractedLower = propagator.extract(headersLower);
        assertThat(Span.fromContext(extractedLower).getSpanContext().isValid()).isTrue();
    }

    @Test
    void shouldNotThrowOnMalformedHeadersAndProvideNewRoot() {
        Map<String, String> headers = Map.of(EventHeaders.TRACEPARENT, "bad-value", EventHeaders.TRACESTATE, "also-bad");
        Context extracted = propagator.extract(headers);
        assertThat(extracted).isEqualTo(Context.root());
        // Should be able to create new span from root without exception
        try (Scope scope = extracted.makeCurrent()) {
            assertThat(Context.current()).isEqualTo(Context.root());
        }
    }

    @Test
    void shouldInjectAndExtractRoundTrip() {
        String traceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String parentId = "bbbbbbbbbbbbbbbb";
        SpanContext sc = SpanContext.create(traceId, parentId, TraceFlags.getSampled(), TraceState.getDefault());
        Span span = Span.wrap(sc);
        Map<String, String> headers = new HashMap<>();
        try (Scope ignored = Context.current().with(span).makeCurrent()) {
            propagator.inject(headers);
        }
        assertThat(headers).containsKey(EventHeaders.TRACEPARENT);
        Context extracted = propagator.extract(headers);
        SpanContext extractedSc = Span.fromContext(extracted).getSpanContext();
        assertThat(extractedSc.getTraceId()).isEqualTo(traceId);
        assertThat(extractedSc.getSpanId()).isEqualTo(parentId);
    }
}
