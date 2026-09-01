package com.seatflow.common.observability.tracing;

import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.logging.MessagingLogContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.Map;

/**
 * Owns all listener-thread trace and MDC state for one Kafka delivery.
 * Tracing is deliberately best-effort: a tracing failure never reaches the listener's business flow.
 */
public final class KafkaListenerTraceScope implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaListenerTraceScope.class);

    private final Scope parentScope;
    private final Span consumerSpan;
    private final Tracer.SpanInScope consumerSpanScope;
    private final MessagingLogContext loggingScope;

    private KafkaListenerTraceScope(Scope parentScope, Span consumerSpan,
                                    Tracer.SpanInScope consumerSpanScope,
                                    MessagingLogContext loggingScope) {
        this.parentScope = parentScope;
        this.consumerSpan = consumerSpan;
        this.consumerSpanScope = consumerSpanScope;
        this.loggingScope = loggingScope;
        this.propagator = null;
        this.tracerProvider = null;
    }

    private final W3cTraceContextPropagator propagator;
    private final ObjectProvider<Tracer> tracerProvider;

    public KafkaListenerTraceScope(W3cTraceContextPropagator propagator, ObjectProvider<Tracer> tracerProvider) {
        this.parentScope = null;
        this.consumerSpan = null;
        this.consumerSpanScope = null;
        this.loggingScope = null;
        this.propagator = propagator;
        this.tracerProvider = tracerProvider;
    }

    public KafkaListenerTraceScope open(EventEnvelope<?> envelope, String topic) {
        Map<String, String> headers = envelope == null ? Collections.emptyMap() : envelope.headers();
        String correlationId = envelope == null ? null : envelope.correlationId();
        String eventType = envelope == null || envelope.eventType() == null ? "unknown" : envelope.eventType();

        return open(headers, correlationId, eventType, topic);
    }

    public KafkaListenerTraceScope open(Map<String, String> headers, String correlationId,
                                        String eventType, String topic) {

        Scope parentScope = null;
        Span consumerSpan = null;
        Tracer.SpanInScope consumerSpanScope = null;
        MessagingLogContext loggingScope = null;
        try {
            Context parent = propagator.extract(headers == null ? Collections.emptyMap() : headers);
            parentScope = parent.makeCurrent();

            Tracer tracer = tracerProvider == null ? null : tracerProvider.getIfAvailable();

            if (tracer != null) {
                consumerSpan = tracer.spanBuilder()
                        .name("kafka consume " + topic)
                        .kind(Span.Kind.CONSUMER)
                        .tag("messaging.system", "kafka")
                        .tag("messaging.destination.name", topic == null ? "unknown" : topic)
                        .tag("messaging.operation", "process")
                        .tag("seatflow.event_type", eventType == null ? "unknown" : eventType)
                        .start();
                consumerSpanScope = tracer.withSpan(consumerSpan);
            }

            loggingScope = MessagingLogContext.open(correlationId, tracer);
            return new KafkaListenerTraceScope(parentScope, consumerSpan, consumerSpanScope, loggingScope);
        } catch (RuntimeException ex) {
            closeQuietly(loggingScope, consumerSpanScope, consumerSpan, parentScope);
            log.debug("Failed to establish Kafka trace scope; continuing without listener tracing. reason={}",
                    ex.getClass().getSimpleName());
            return new KafkaListenerTraceScope(null, null, null, null);
        }
    }

    @Override
    public void close() {
        closeQuietly(loggingScope, consumerSpanScope, consumerSpan, parentScope);
    }

    private static void closeQuietly(MessagingLogContext loggingScope, Tracer.SpanInScope consumerSpanScope,
                                     Span consumerSpan, Scope parentScope) {
        try {
            if (loggingScope != null) {
                loggingScope.close();
            }
        } catch (RuntimeException ignored) {
            // Scope cleanup must not change Kafka acknowledgement behaviour.
        }
        try {
            if (consumerSpanScope != null) {
                consumerSpanScope.close();
            }
        } catch (RuntimeException ignored) {
            // Scope cleanup must not change Kafka acknowledgement behaviour.
        }
        try {
            if (consumerSpan != null) {
                consumerSpan.end();
            }
        } catch (RuntimeException ignored) {
            // Scope cleanup must not change Kafka acknowledgement behaviour.
        }
        try {
            if (parentScope != null) {
                parentScope.close();
            }
        } catch (RuntimeException ignored) {
            // Scope cleanup must not change Kafka acknowledgement behaviour.
        }
    }
}
