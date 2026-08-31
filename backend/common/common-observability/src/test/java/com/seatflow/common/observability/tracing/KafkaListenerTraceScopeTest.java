package com.seatflow.common.observability.tracing;

import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventHeaders;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.logging.StructuredLogFields;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaListenerTraceScopeTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_ID = "00f067aa0ba902b7";
    private static final String CONSUMER_ID = "1111111111111111";

    @AfterEach
    void clearContext() {
        MDC.clear();
        CorrelationContext.clear();
    }

    @Test
    void shouldPreserveEnvelopeCorrelationAndExposeCurrentConsumerSpanInMdc() {
        String correlationId = UUID.randomUUID().toString();
        Tracer tracer = mock(Tracer.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span consumerSpan = mock(Span.class);
        Tracer.SpanInScope spanInScope = mock(Tracer.SpanInScope.class);
        TraceContext consumerContext = mock(TraceContext.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        when(tracer.spanBuilder()).thenReturn(builder);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.kind(Span.Kind.CONSUMER)).thenReturn(builder);
        when(builder.tag(anyString(), anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(consumerSpan);
        when(tracer.withSpan(consumerSpan)).thenReturn(spanInScope);
        when(tracer.currentSpan()).thenReturn(consumerSpan);
        when(consumerSpan.context()).thenReturn(consumerContext);
        when(consumerContext.traceId()).thenReturn(TRACE_ID);
        when(consumerContext.spanId()).thenReturn(CONSUMER_ID);

        KafkaListenerTraceScope factory = new KafkaListenerTraceScope(new W3cTraceContextPropagator(), provider);
        EventEnvelope<String> envelope = envelope(correlationId, validHeaders());
        MDC.put("outer", "value");
        CorrelationContext.setCorrelationId("outer-correlation");

        try (KafkaListenerTraceScope ignored = factory.open(envelope, "seatflow.payment.events")) {
            assertThat(MDC.get(StructuredLogFields.CORRELATION_ID)).isEqualTo(correlationId);
            assertThat(MDC.get(StructuredLogFields.TRACE_ID)).isEqualTo(TRACE_ID);
            assertThat(MDC.get(StructuredLogFields.SPAN_ID)).isEqualTo(CONSUMER_ID);
            assertThat(MDC.get("outer")).isNull();
            assertThat(CorrelationContext.getCorrelationId()).contains(correlationId);
        }

        verify(builder).kind(Span.Kind.CONSUMER);
        verify(spanInScope).close();
        verify(consumerSpan).end();
        assertThat(MDC.get("outer")).isEqualTo("value");
        assertThat(CorrelationContext.getCorrelationId()).contains("outer-correlation");
    }

    @Test
    void shouldRestoreMdcAndCorrelationAfterBusinessFailure() {
        KafkaListenerTraceScope factory = new KafkaListenerTraceScope(new W3cTraceContextPropagator(), null);
        String correlationId = UUID.randomUUID().toString();
        MDC.put("outer", "value");
        CorrelationContext.setCorrelationId("outer-correlation");

        assertThatThrownBy(() -> {
            try (KafkaListenerTraceScope ignored = factory.open(envelope(correlationId, validHeaders()), "topic")) {
                assertThat(MDC.get(StructuredLogFields.CORRELATION_ID)).isEqualTo(correlationId);
                throw new IllegalStateException("business failure");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get("outer")).isEqualTo("value");
        assertThat(CorrelationContext.getCorrelationId()).contains("outer-correlation");
    }

    private static EventEnvelope<String> envelope(String correlationId, Map<String, String> headers) {
        return new EventEnvelope<>(UUID.randomUUID().toString(), "PaymentCompleted", Instant.now(), correlationId,
                null, UUID.randomUUID().toString(), EventEnvelope.CURRENT_VERSION, "payload", headers);
    }

    private static Map<String, String> validHeaders() {
        return Map.of(EventHeaders.TRACEPARENT, "00-" + TRACE_ID + "-" + PARENT_ID + "-01");
    }
}
