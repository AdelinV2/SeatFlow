package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.ReservationCancelledEvent;
import com.seatflow.realtime.messaging.event.ReservationExpiredEvent;
import com.seatflow.realtime.messaging.event.ReservationHeldEvent;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import com.seatflow.common.observability.context.CorrelationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    private final SeatStatusBroadcaster seatStatusBroadcaster;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = EventTopics.RESERVATION_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:realtime-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleReservationEvent(EventEnvelope<?> envelope) {
        if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
            log.warn("Received invalid envelope (null envelope, missing eventType, or null payload), skipping message");
            return;
        }

        String correlationId = envelope.correlationId() != null ? envelope.correlationId() : "";
        CorrelationContext.setCorrelationId(correlationId);
        MDC.put("correlationId", correlationId);
        if (envelope.eventId() != null) {
            MDC.put("traceId", envelope.eventId());
        }

        try {
            log.info("Received reservation event: type={}, eventId={}, aggregateId={}",
                    envelope.eventType(), envelope.eventId(), envelope.aggregateId());

            switch (envelope.eventType()) {
                case "ReservationHeld" -> {
                    ReservationHeldEvent event = convertPayload(envelope.payload(), ReservationHeldEvent.class);
                    seatStatusBroadcaster.broadcastSeatStatus(
                            event.eventId(),
                            event.seatIds(),
                            SeatStatus.HELD,
                            event.expiresAt()
                    );
                }
                case "ReservationExpired" -> {
                    ReservationExpiredEvent event = convertPayload(envelope.payload(), ReservationExpiredEvent.class);
                    seatStatusBroadcaster.broadcastSeatStatus(
                            event.eventId(),
                            event.seatIds(),
                            SeatStatus.AVAILABLE,
                            null
                    );
                }
                case "ReservationCancelled" -> {
                    ReservationCancelledEvent event = convertPayload(envelope.payload(), ReservationCancelledEvent.class);
                    seatStatusBroadcaster.broadcastSeatStatus(
                            event.eventId(),
                            event.seatIds(),
                            SeatStatus.AVAILABLE,
                            null
                    );
                }
                default -> log.debug("Ignoring reservation event type: {}", envelope.eventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process reservation event: type={}, eventId={}: {}",
                    envelope.eventType(), envelope.eventId(), ex.getMessage(), ex);
            throw ex;
        } finally {
            MDC.remove("correlationId");
            MDC.remove("traceId");
            CorrelationContext.clear();
        }
    }

    private <T> T convertPayload(Object payload, Class<T> targetClass) {
        if (targetClass.isInstance(payload)) {
            return targetClass.cast(payload);
        }
        return objectMapper.convertValue(payload, targetClass);
    }
}
