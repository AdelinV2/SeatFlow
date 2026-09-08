package com.seatflow.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.analytics.metrics.AnalyticsConsumerMetrics;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka ingestion boundary for the analytics read model.
 *
 * <p>Subscribes to the canonical reservation / payment / ticket / event topic families with the
 * stable {@code analytics-service-v1} group. Never performs synchronous REST calls to
 * reservation/payment/ticket/event services and never reads another service database: missing
 * correlation (e.g. {@code PaymentCompleted} before {@code ReservationHeldEvent}) is temporary
 * read-model state delivered to P14-003 reconciliation, not a reason to block, requeue, or look
 * up the source service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsEventConsumer {

    private final ObjectMapper objectMapper;
    private final AnalyticsEventDispatcher dispatcher;
    private final ProjectionEventProcessor processor;
    private final AnalyticsConsumerMetrics metrics;
    private final KafkaListenerTraceScope traceScope;

    @KafkaListener(
            topics = {
                    EventTopics.RESERVATION_EVENTS,
                    EventTopics.PAYMENT_EVENTS,
                    EventTopics.TICKET_EVENTS,
                    EventTopics.EVENT_EVENTS
            },
            groupId = "analytics-service-v1",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void listen(String message,
                       @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                       @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                       @Header(KafkaHeaders.OFFSET) long offset,
                       @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        JsonNode root = readEnvelopeRoot(message, topic, partition, offset);
        String eventType = root.path("eventType").asText(null);
        String eventId = root.path("eventId").asText(null);
        String correlationId = root.path("correlationId").asText(null);

        try (KafkaListenerTraceScope ignored =
                     traceScope.open(extractHeaders(root), correlationId, eventType, topic)) {

            if (!dispatcher.isSupported(eventType)) {
                log.debug("Ignoring non-analytics event. topic={}, eventType={}, eventId={}, partition={}, offset={}",
                        topic, eventType, eventId, partition, offset);
                metrics.incrementIgnored(eventType);
                return;
            }

            EventEnvelope<JsonNode> envelope;
            try {
                envelope = dispatcher.parseAndValidate(root, topic);
            } catch (AnalyticsEventValidationException ex) {
                metrics.incrementFailed(ex.getEventType(), "VALIDATION");
                throw ex;
            }
            ConsumerRecordMetadata metadata = new ConsumerRecordMetadata(topic, partition, offset, key);
            try {
                ProjectionEventProcessor.Outcome outcome = processor.process(envelope, metadata);
                switch (outcome) {
                    case PROCESSED -> metrics.incrementProcessed(envelope.eventType());
                    case DUPLICATE -> metrics.incrementDuplicate(envelope.eventType());
                    case IGNORED -> metrics.incrementIgnored(envelope.eventType());
                }
            } catch (AnalyticsEventValidationException ex) {
                metrics.incrementFailed(ex.getEventType(), "VALIDATION");
                throw ex;
            } catch (RuntimeException ex) {
                metrics.incrementFailed(envelope.eventType(), "TRANSIENT");
                throw ex;
            }
        }
    }

    private JsonNode readEnvelopeRoot(String message, String topic, int partition, long offset) {
        if (message == null || message.isBlank()) {
            throw new AnalyticsEventValidationException(null, null,
                    "Empty analytics envelope on topic " + topic);
        }
        try {
            return objectMapper.readTree(message);
        } catch (Exception ex) {
            log.error("Failed to parse analytics envelope. topic={}, partition={}, offset={}",
                    topic, partition, offset, ex);
            throw new AnalyticsEventValidationException(null, null,
                    "Unparseable analytics envelope on topic " + topic, ex);
        }
    }

    private Map<String, String> extractHeaders(JsonNode root) {
        try {
            JsonNode headersNode = root.path("headers");
            if (headersNode.isObject()) {
                Map<String, String> map = new HashMap<>();
                headersNode.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
                return map;
            }
        } catch (RuntimeException ignored) {
            // Header extraction is best-effort; tracing must not change acknowledgement behaviour.
        }
        return Collections.emptyMap();
    }
}
