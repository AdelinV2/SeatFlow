package com.seatflow.ticket.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.ticket.messaging.event.TicketIssuedEvent;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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

    private static final int MAX_RETRY_COUNT = 5;
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${outbox.publisher.topic:" + EventTopics.TICKET_EVENTS + "}")
    private String topic = EventTopics.TICKET_EVENTS;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    public void publishPendingEvents() {
        // Claim a batch in its own short transaction (FOR UPDATE SKIP LOCKED) and release
        // row locks immediately. Blocking Kafka send must NOT be inside a DB transaction,
        // otherwise a slow broker holds PostgreSQL row locks and exhausts the pool.
        List<OutboxEvent> pendingEvents = claimPendingEvents();
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Polling unpublished ticket outbox events: count={}", pendingEvents.size());

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
                        topic,
                        event.getAggregateId().toString(),
                        envelope
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                int updated = outboxRepository.markPublished(event.getId(), Instant.now());
                if (updated > 0) {
                    log.info("Ticket outbox event published successfully. outboxId={}, aggregateId={}, eventType={}, topic={}",
                            event.getId(), event.getAggregateId(), event.getEventType(), topic);
                }

            } catch (Exception ex) {
                int updated = outboxRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (updated == 0) {
                    log.error("Ticket outbox delivery failed; exceeded max retry limit ({}). outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            MAX_RETRY_COUNT, event.getId(), event.getEventType(), event.getAggregateId(),
                            MAX_RETRY_COUNT, ex);
                    meterRegistry.counter("seatflow.outbox.dead.letter.total", "eventType", event.getEventType()).increment();
                } else {
                    log.error("Ticket outbox delivery failed; retry incremented. outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount() + 1, ex);
                }
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimPendingEvents() {
        return outboxRepository.findUnpublishedForUpdate(MAX_RETRY_COUNT, batchSize);
    }

    private Object deserializePayload(OutboxEvent event) throws Exception {
        if ("TicketIssued".equals(event.getEventType())) {
            return objectMapper.readValue(event.getPayload(), TicketIssuedEvent.class);
        }
        return objectMapper.readTree(event.getPayload());
    }
}
