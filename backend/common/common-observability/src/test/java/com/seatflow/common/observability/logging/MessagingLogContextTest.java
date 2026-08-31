package com.seatflow.common.observability.logging;

import com.seatflow.common.observability.context.CorrelationContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingLogContextTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
        CorrelationContext.clear();
    }

    @Test
    void shouldGenerateCorrelationAndRestoreOuterContextWithoutUsingEventIdAsTraceId() {
        MDC.put("outer.key", "outer-value");
        CorrelationContext.setCorrelationId("outer-correlation");

        try (MessagingLogContext ignored = MessagingLogContext.open("not-a-uuid", null)) {
            String generated = MDC.get(StructuredLogFields.CORRELATION_ID);
            assertThat(UUID.fromString(generated)).isNotNull();
            assertThat(MDC.get(StructuredLogFields.TRACE_ID)).isNull();
            assertThat(CorrelationContext.getCorrelationId()).contains(generated);
        }

        assertThat(MDC.getCopyOfContextMap()).containsEntry("outer.key", "outer-value");
        assertThat(CorrelationContext.getCorrelationId()).contains("outer-correlation");
    }

    @Test
    void shouldExposeOnlyValidActiveMicrometerTraceContext() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");
        when(traceContext.spanId()).thenReturn("00f067aa0ba902b7");

        try (MessagingLogContext ignored = MessagingLogContext.open(UUID.randomUUID().toString(), tracer)) {
            assertThat(MDC.get(StructuredLogFields.TRACE_ID)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(MDC.get(StructuredLogFields.SPAN_ID)).isEqualTo("00f067aa0ba902b7");
        }

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        assertThat(CorrelationContext.getCorrelationId()).isEmpty();
    }
}
