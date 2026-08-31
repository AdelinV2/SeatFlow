package com.seatflow.notification.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.logging.MessagingLogContext;
import com.seatflow.notification.messaging.event.PaymentFailedEvent;
import io.micrometer.tracing.Tracer;
import com.seatflow.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailedEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<Tracer> tracerProvider;

    @KafkaListener(
            topics = EventTopics.PAYMENT_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:notification-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePaymentEvent(EventEnvelope<?> envelope) {
        if (envelope == null || envelope.eventType() == null || envelope.payload() == null) {
            log.warn("Received invalid envelope (null envelope, missing eventType, or null payload), skipping message");
            return;
        }

        try (MessagingLogContext ignored = MessagingLogContext.open(envelope.correlationId(), tracerProvider)) {
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
                    envelope.eventType(), envelope.eventId(), ex.getMessage(), ex);
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
