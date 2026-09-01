package com.seatflow.reservation.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.reservation.messaging.event.UserRegisteredEvent;
import com.seatflow.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventsConsumer {

    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;
    private final KafkaListenerTraceScope kafkaListenerTraceScope;

    @KafkaListener(topics = EventTopics.USER_EVENTS, groupId = "reservation-service")
    public void listen(String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        try (KafkaListenerTraceScope ignored = kafkaListenerTraceScope.open(
                extractHeaders(message), extractCorrelationId(message), "UserRegistered", topic)) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").asText();
            String eventId = root.path("eventId").asText();
            String correlationId = root.path("correlationId").asText();
            String aggregateId = root.path("aggregateId").asText();

            log.info("Processing Kafka event. topic={}, eventType={}, eventId={}, aggregateId={}, correlationId={}, partition={}, offset={}",
                    topic, eventType, eventId, aggregateId, correlationId, partition, offset);

            switch (eventType) {
                case "UserRegistered" -> {
                    EventEnvelope<UserRegisteredEvent> envelope = objectMapper.readValue(
                            message, new TypeReference<EventEnvelope<UserRegisteredEvent>>() {
                            });
                    UserRegisteredEvent payload = envelope.payload();
                    int linked = reservationService.claimGuestReservations(
                            UUID.fromString(payload.userId()), payload.email());
                    log.info("Guest reservations linked to registered user. userId={}, email={}, linkedCount={}",
                            payload.userId(), payload.email(), linked);
                }
                case "PaymentCompleted" ->
                        log.warn("Unexpected PaymentCompleted event received on user topic. eventId={}, aggregateId={}",
                                eventId, aggregateId);
                default ->
                        log.warn("Unsupported event type on user topic. eventType={}, eventId={}, aggregateId={}",
                                eventType, eventId, aggregateId);
            }
        } catch (JsonProcessingException ex) {
            log.error("Failed to parse user event. topic={}, partition={}, offset={}", topic, partition, offset, ex);
            throw new RuntimeException("Failed to parse user event", ex);
        } catch (RuntimeException ex) {
            log.error("Failed to process user event. topic={}, partition={}, offset={}", topic, partition, offset, ex);
            throw new RuntimeException("Failed to process user event", ex);
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
        } catch (Exception ignored) {
        }
        return Collections.emptyMap();
    }
}
