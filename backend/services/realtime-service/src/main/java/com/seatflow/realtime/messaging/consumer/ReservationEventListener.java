package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.ReservationCancelledEvent;
import com.seatflow.realtime.messaging.event.ReservationConfirmedEvent;
import com.seatflow.realtime.messaging.event.ReservationExpiredEvent;
import com.seatflow.realtime.messaging.event.ReservationHeldEvent;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.service.RealtimeFanOutPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    private final RealtimeFanOutPublisher realtimeFanOutPublisher;
    private final ObjectMapper objectMapper;
    private final KafkaListenerTraceScope kafkaListenerTraceScope;

    @KafkaListener(
            topics = EventTopics.RESERVATION_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:realtime-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleReservationEvent(EventEnvelope<?> envelope) {
        try (KafkaListenerTraceScope ignored = kafkaListenerTraceScope.open(envelope, EventTopics.RESERVATION_EVENTS)) {
            if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
                log.warn("Received invalid envelope (null envelope, missing eventType, or null payload), skipping message");
                return;
            }
            log.info("Received reservation event: type={}, eventId={}, aggregateId={}",
                    envelope.eventType(), envelope.eventId(), envelope.aggregateId());
            switch (envelope.eventType()) {
                case "ReservationHeld", "ReservationHeldEvent" -> {
                    ReservationHeldEvent event = convertPayload(envelope.payload(), ReservationHeldEvent.class);
                    publish(envelope.eventId(), event.eventId(), event.seatIds(), SeatStatus.HELD, event.expiresAt());
                }
                case "ReservationExpired", "ReservationExpiredEvent" -> {
                    ReservationExpiredEvent event = convertPayload(envelope.payload(), ReservationExpiredEvent.class);
                    publish(envelope.eventId(), event.eventId(), event.seatIds(), SeatStatus.AVAILABLE, null);
                }
                case "ReservationCancelled", "ReservationCancelledEvent" -> {
                    ReservationCancelledEvent event = convertPayload(envelope.payload(), ReservationCancelledEvent.class);
                    publish(envelope.eventId(), event.eventId(), event.seatIds(), SeatStatus.AVAILABLE, null);
                }
                case "ReservationConfirmed", "ReservationConfirmedEvent" -> {
                    ReservationConfirmedEvent event = convertPayload(envelope.payload(), ReservationConfirmedEvent.class);
                    publish(envelope.eventId(), event.eventId(), event.seatIds(), SeatStatus.SOLD, null);
                }
                default -> log.debug("Ignoring reservation event type: {}", envelope.eventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process reservation event: type={}, eventId={}: {}",
                    envelope != null ? envelope.eventType() : "null", envelope != null ? envelope.eventId() : "null", ex.getMessage(), ex);
            throw ex;
        }
    }

    private <T> T convertPayload(Object payload, Class<T> targetClass) {
        if (targetClass.isInstance(payload)) {
            return targetClass.cast(payload);
        }
        return objectMapper.convertValue(payload, targetClass);
    }

    private void publish(String sourceEventId, java.util.UUID eventId, java.util.List<java.util.UUID> seatIds,
                         SeatStatus status, java.time.Instant holdExpiresAt) {
        realtimeFanOutPublisher.publish(sourceEventId, SeatStatusUpdateMessage.of(eventId, seatIds, status, holdExpiresAt));
    }
}
