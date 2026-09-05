package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.TicketIssuedEvent;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.service.RealtimeFanOutPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketEventListener {

    private final RealtimeFanOutPublisher realtimeFanOutPublisher;
    private final ObjectMapper objectMapper;
    private final KafkaListenerTraceScope kafkaListenerTraceScope;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = EventTopics.TICKET_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:realtime-service}",
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
            if ("TicketIssued".equals(envelope.eventType())) {
                TicketIssuedEvent event = convertPayload(envelope.payload(), TicketIssuedEvent.class);
                if (event.eventSessionId() == null) {
                    meterRegistry.counter("seatflow.realtime.ticket.discarded").increment();
                    log.warn("Discarding ticket realtime event without eventSessionId: "
                            + "legacy event-only messages are never routed by session inference. "
                            + "sourceEventId={}, eventId={}", envelope.eventId(), event.eventId());
                    return;
                }
                if (event.seatId() == null) {
                    meterRegistry.counter("seatflow.realtime.ticket.discarded").increment();
                    log.warn("Discarding ticket realtime event with no seat: sourceEventId={}, eventSessionId={}",
                            envelope.eventId(), event.eventSessionId());
                    return;
                }
                // Realtime is fan-out only: duplicate Kafka deliveries repeat the same public seat
                // state and never mutate authoritative booking state.
                realtimeFanOutPublisher.publish(envelope.eventId(),
                        SeatStatusUpdateMessage.of(event.eventSessionId(), event.eventId(), event.seatId(),
                                SeatStatus.SOLD));
            } else {
                log.debug("Ignoring ticket event type: {}", envelope.eventType());
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
