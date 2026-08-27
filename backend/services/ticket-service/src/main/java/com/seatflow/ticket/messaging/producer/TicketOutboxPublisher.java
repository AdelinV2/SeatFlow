package com.seatflow.ticket.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.ticket.messaging.event.TicketIssuedEvent;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketOutboxPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 5L;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Polling unpublished outbox events: count={}", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                Object payload = deserializePayload(event);
                String correlationId = CorrelationContext.getCorrelationId().orElse(null);
                if (correlationId == null || correlationId.isBlank()) {
                    correlationId = "outbox-" + UUID.randomUUID();
                }

                EventEnvelope<Object> envelope = new EventEnvelope<>(
                        UUID.randomUUID().toString(),
                        event.getEventType(),
                        event.getCreatedAt(),
                        correlationId,
                        null,
                        event.getAggregateId().toString(),
                        1,
                        payload
                );

                CompletableFuture<SendResult<String, Object>> sendFuture = kafkaTemplate.send(
                        EventTopics.TICKET_EVENTS,
                        event.getAggregateId().toString(),
                        envelope
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                event.setPublishedAt(Instant.now());
                outboxRepository.save(event);

                log.info("Ticket outbox event published successfully. topic={}, aggregateId={}, eventType={}",
                        EventTopics.TICKET_EVENTS, event.getAggregateId(), event.getEventType());

            } catch (Exception ex) {
                log.error("Error publishing ticket outbox event. aggregateId={}, eventType={}, retryCount={}",
                        event.getAggregateId(), event.getEventType(), event.getRetryCount(), ex);

                if (event.getRetryCount() < 5) {
                    event.setRetryCount(event.getRetryCount() + 1);
                    outboxRepository.save(event);
                }
            }
        }
    }

    private Object deserializePayload(OutboxEvent event) throws Exception {
        if ("TicketIssued".equals(event.getEventType())) {
            return objectMapper.readValue(event.getPayload(), TicketIssuedEvent.class);
        }
        return objectMapper.readTree(event.getPayload());
    }
}
