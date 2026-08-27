package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.TicketIssuedEvent;
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
public class TicketEventListener {

    private final SeatStatusBroadcaster seatStatusBroadcaster;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = EventTopics.TICKET_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:realtime-service}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleTicketEvent(EventEnvelope<?> envelope) {
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
            log.info("Received ticket event: type={}, eventId={}, aggregateId={}",
                    envelope.eventType(), envelope.eventId(), envelope.aggregateId());

            if ("TicketIssued".equals(envelope.eventType())) {
                TicketIssuedEvent event = convertPayload(envelope.payload(), TicketIssuedEvent.class);
                seatStatusBroadcaster.broadcastSeatStatus(
                        event.eventId(),
                        event.seatId(),
                        SeatStatus.SOLD
                );
            } else {
                log.debug("Ignoring ticket event type: {}", envelope.eventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process ticket event: type={}, eventId={}: {}",
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
