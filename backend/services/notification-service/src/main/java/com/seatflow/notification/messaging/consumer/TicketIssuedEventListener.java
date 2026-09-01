package com.seatflow.notification.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.notification.messaging.event.TicketIssuedEvent;
import com.seatflow.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketIssuedEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final KafkaListenerTraceScope kafkaListenerTraceScope;

    @KafkaListener(
            topics = EventTopics.TICKET_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:notification-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleTicketEvent(EventEnvelope<?> envelope) {
        try (KafkaListenerTraceScope ignored = kafkaListenerTraceScope.open(envelope, EventTopics.TICKET_EVENTS)) {
            if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
                log.warn("Received invalid envelope (null envelope, missing eventType, or null payload), skipping message");
                return;
            }
            log.info("Received ticket event: type={}, eventId={}, aggregateId={}",
                    envelope.eventType(), envelope.eventId(), envelope.aggregateId());
            if ("TicketIssued".equalsIgnoreCase(envelope.eventType()) || "TicketIssuedEvent".equalsIgnoreCase(envelope.eventType())) {
                TicketIssuedEvent event = convertPayload(envelope.payload(), TicketIssuedEvent.class);
                notificationService.sendTicketIssuedNotification(event);
            } else {
                log.debug("Ignoring unsupported ticket event type: {}", envelope.eventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process ticket event: type={}, eventId={}: {}",
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
}
