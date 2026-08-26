package com.seatflow.reservation.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.reservation.messaging.event.PaymentCompletedEvent;
import com.seatflow.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventsConsumer {

    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = EventTopics.PAYMENT_EVENTS, groupId = "reservation-service")
    public void listen(String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").asText();
            String eventId = root.path("eventId").asText();
            String correlationId = root.path("correlationId").asText();
            String aggregateId = root.path("aggregateId").asText();

            log.info("Processing Kafka event. topic={}, eventType={}, eventId={}, aggregateId={}, correlationId={}, partition={}, offset={}",
                    topic, eventType, eventId, aggregateId, correlationId, partition, offset);

            switch (eventType) {
                case "PaymentCompleted" -> {
                    EventEnvelope<PaymentCompletedEvent> envelope = objectMapper.readValue(
                            message, new TypeReference<EventEnvelope<PaymentCompletedEvent>>() {
                            });
                    PaymentCompletedEvent payload = envelope.payload();
                    reservationService.confirmReservation(
                            UUID.fromString(payload.reservationId()),
                            UUID.fromString(payload.paymentId()));
                }
                case "UserRegistered" ->
                        log.warn("Unexpected UserRegistered event received on payment topic. eventId={}, aggregateId={}",
                                eventId, aggregateId);
                default ->
                        log.warn("Unsupported event type on payment topic. eventType={}, eventId={}, aggregateId={}",
                                eventType, eventId, aggregateId);
            }
        } catch (JsonProcessingException ex) {
            log.error("Failed to parse payment event. topic={}, partition={}, offset={}", topic, partition, offset, ex);
            throw new RuntimeException("Failed to parse payment event", ex);
        } catch (RuntimeException ex) {
            log.error("Failed to process payment event. topic={}, partition={}, offset={}", topic, partition, offset, ex);
            throw new RuntimeException("Failed to process payment event", ex);
        }
    }
}
