package com.seatflow.payment.messaging.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.payment.messaging.event.UserRegisteredEvent;
import com.seatflow.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventsConsumer {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;
    private final KafkaListenerTraceScope kafkaListenerTraceScope;

    @KafkaListener(topics = EventTopics.USER_EVENTS, groupId = "payment-service")
    public void handleUserEvents(
            String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        try (KafkaListenerTraceScope ignored = kafkaListenerTraceScope.open(
                extractHeaders(message), extractCorrelationId(message), "UserRegistered", topic)) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);
            String eventType = rootNode.path("eventType").asText();
            String eventId = rootNode.path("eventId").asText();
            String correlationId = rootNode.path("correlationId").asText();
            String aggregateId = rootNode.path("aggregateId").asText();

            log.info("Processing Kafka event. topic={}, eventType={}, eventId={}, aggregateId={}, correlationId={}, partition={}, offset={}",
                    topic, eventType, eventId, aggregateId, correlationId, partition, offset);

            if ("UserRegistered".equalsIgnoreCase(eventType)) {
                EventEnvelope<UserRegisteredEvent> envelope = objectMapper.readValue(
                        message,
                        new TypeReference<EventEnvelope<UserRegisteredEvent>>() {}
                );
                UserRegisteredEvent payload = envelope.payload();
                int linked = paymentService.claimGuestPayments(payload.userId(), payload.email());
                log.info("Guest payments linked to registered user. userId={}, email={}, linkedCount={}",
                        payload.userId(), payload.email(), linked);
            } else {
                log.debug("Ignored irrelevant user event type on topic {}: {}", topic, eventType);
            }
        } catch (Exception ex) {
            log.error("Failed to process user event message: topic={}, partition={}, offset={}", topic, partition, offset, ex);
            throw new RuntimeException("Error processing UserRegistered event", ex);
        }
        }
    }

    private String extractCorrelationId(String message) {
        try {
            return objectMapper.readTree(message).path("correlationId").asText(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> extractHeaders(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode headersNode = root.path("headers");
            if (headersNode.isObject()) {
                Map<String, String> map = new java.util.HashMap<>();
                headersNode.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
                return map;
            }
        } catch (Exception ignored) {}
        return Collections.emptyMap();
    }
}
