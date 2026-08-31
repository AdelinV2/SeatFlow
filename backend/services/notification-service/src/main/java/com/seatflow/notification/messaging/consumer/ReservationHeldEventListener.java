package com.seatflow.notification.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.logging.StructuredLogFields;
import com.seatflow.notification.messaging.event.ReservationHeldEvent;
import com.seatflow.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationHeldEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = EventTopics.RESERVATION_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:notification-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleReservationEvent(EventEnvelope<?> envelope) {
        if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
            log.warn("Received invalid envelope (null envelope, missing eventType, or null payload), skipping message");
            return;
        }

        String correlationId = envelope.correlationId() != null ? envelope.correlationId() : "";
        CorrelationContext.setCorrelationId(correlationId);
        MDC.put(StructuredLogFields.CORRELATION_ID, correlationId);
        if (envelope.eventId() != null) {
            MDC.put(StructuredLogFields.TRACE_ID, envelope.eventId());
        }

        try {
            log.info("Received reservation event: type={}, eventId={}, aggregateId={}",
                    envelope.eventType(), envelope.eventId(), envelope.aggregateId());

            if ("ReservationHeld".equalsIgnoreCase(envelope.eventType()) || "ReservationHeldEvent".equalsIgnoreCase(envelope.eventType())) {
                ReservationHeldEvent event = convertPayload(envelope.payload(), ReservationHeldEvent.class);
                notificationService.sendReservationHeldNotification(event);
            } else {
                log.debug("Ignoring unsupported reservation event type: {}", envelope.eventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process reservation event: type={}, eventId={}: {}",
                    envelope.eventType(), envelope.eventId(), ex.getMessage(), ex);
            throw ex;
        } finally {
            MDC.remove(StructuredLogFields.CORRELATION_ID);
            MDC.remove(StructuredLogFields.TRACE_ID);
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
