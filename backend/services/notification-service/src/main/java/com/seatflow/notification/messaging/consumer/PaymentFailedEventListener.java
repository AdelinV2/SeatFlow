package com.seatflow.notification.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.notification.messaging.event.PaymentFailedEvent;
import com.seatflow.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailedEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final KafkaListenerTraceScope kafkaListenerTraceScope;

    @KafkaListener(
            topics = EventTopics.PAYMENT_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:notification-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePaymentEvent(EventEnvelope<?> envelope) {
        try (KafkaListenerTraceScope ignored = kafkaListenerTraceScope.open(envelope, EventTopics.PAYMENT_EVENTS)) {
            if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
                log.warn("Received invalid envelope (null envelope, missing eventType, or null payload), skipping message");
                return;
            }
            log.info("Received payment event: type={}, eventId={}, aggregateId={}",
                    envelope.eventType(), envelope.eventId(), envelope.aggregateId());
            if ("PaymentFailed".equalsIgnoreCase(envelope.eventType()) || "PaymentFailedEvent".equalsIgnoreCase(envelope.eventType())) {
                PaymentFailedEvent event = convertPayload(envelope.payload(), PaymentFailedEvent.class);
                notificationService.sendPaymentFailedNotification(event);
            } else {
                log.debug("Ignoring unsupported payment event type: {}", envelope.eventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process payment event: type={}, eventId={}: {}",
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
