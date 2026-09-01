package com.seatflow.ticket.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.context.CorrelationContext;
import com.seatflow.common.observability.metrics.MetricTagPolicy;
import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.ticket.messaging.event.TicketIssuedEvent;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final W3cTraceContextPropagator w3cTraceContextPropagator;

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
                EventEnvelope<Object> envelopeToSend = null;
                // Try to interpret stored payload as already-enveloped JSON (new format with headers)
                try {
                    String raw = event.getPayload();
                    if (raw != null && raw.trim().startsWith("{") && raw.contains("\"eventId\"")) {
                        com.fasterxml.jackson.core.type.TypeReference<EventEnvelope<com.fasterxml.jackson.databind.JsonNode>> tr =
                                new com.fasterxml.jackson.core.type.TypeReference<EventEnvelope<com.fasterxml.jackson.databind.JsonNode>>() {};
                        EventEnvelope<com.fasterxml.jackson.databind.JsonNode> stored = objectMapper.readValue(raw, tr);
                        if (stored.eventId() != null && stored.payload() != null) {
                            Map<String, String> headers = new HashMap<>(stored.headers());
                            if (!headers.containsKey(com.seatflow.common.events.EventHeaders.TRACEPARENT)) {
                                Map<String, String> injected = new HashMap<>();
                                try {
                                    if (w3cTraceContextPropagator != null) {
                                        w3cTraceContextPropagator.inject(injected);
                                    }
                                } catch (Exception ignored) {
                                }
                                headers.putAll(injected);
                            }
                            Object payloadObj = stored.payload();
                            // If payload is JsonNode of TicketIssuedEvent, convert to typed event if possible
                            if ("TicketIssued".equals(stored.eventType()) && payloadObj instanceof com.fasterxml.jackson.databind.JsonNode) {
                                try {
                                    payloadObj = objectMapper.treeToValue((com.fasterxml.jackson.databind.JsonNode) payloadObj, TicketIssuedEvent.class);
                                } catch (Exception ignored) {
                                }
                            }
                            envelopeToSend = new EventEnvelope<>(
                                    stored.eventId(),
                                    stored.eventType(),
                                    stored.occurredAt(),
                                    stored.correlationId(),
                                    stored.causationId(),
                                    stored.aggregateId(),
                                    stored.version(),
                                    payloadObj,
                                    headers
                            );
                        }
                    }
                } catch (Exception ignored) {
                    envelopeToSend = null;
                }

                if (envelopeToSend == null) {
                    Object payload = deserializePayload(event);
                    String correlationId = CorrelationContext.getCorrelationId().orElse(null);
                    if (correlationId == null || correlationId.isBlank()) {
                        correlationId = "outbox-" + UUID.randomUUID();
                    }
                    Map<String, String> headers = new HashMap<>();
                    try {
                        if (w3cTraceContextPropagator != null) {
                            w3cTraceContextPropagator.inject(headers);
                        }
                    } catch (Exception ignored) {
                    }
                    envelopeToSend = new EventEnvelope<>(
                            UUID.randomUUID().toString(),
                            event.getEventType(),
                            event.getCreatedAt(),
                            correlationId,
                            null,
                            event.getAggregateId().toString(),
                            1,
                            payload,
                            headers
                    );
                }

                CompletableFuture<SendResult<String, Object>> sendFuture = kafkaTemplate.send(
                        topic,
                        event.getAggregateId().toString(),
                        envelopeToSend
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                Instant publishedAt = Instant.now();
                int updated = outboxRepository.markPublished(event.getId(), publishedAt);
                if (updated > 0) {
                    log.info("Ticket outbox event published successfully. outboxId={}, aggregateId={}, eventType={}, topic={}",
                            event.getId(), event.getAggregateId(), event.getEventType(), topic);
                    safeRecordOutboxLatency(event, publishedAt, "SUCCESS");
                } else {
                    log.warn("Ticket outbox acknowledgement was not persisted; success metric suppressed. outboxId={}",
                            event.getId());
                }

            } catch (Exception ex) {
                int updated = outboxRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (updated == 0) {
                    log.error("Ticket outbox delivery failed; exceeded max retry limit ({}). outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            MAX_RETRY_COUNT, event.getId(), event.getEventType(), event.getAggregateId(),
                            MAX_RETRY_COUNT, ex);
                    safeIncrementDeadLetter(event.getEventType());
                    safeRecordOutboxLatency(event, Instant.now(), "FAILED");
                } else {
                    log.error("Ticket outbox delivery failed; retry incremented. outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount() + 1, ex);
                    safeIncrementRetry(event.getEventType());
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

    private void safeRecordOutboxLatency(OutboxEvent event, Instant publishedAt, String outcome) {
        try {
            Instant createdAt = event.getCreatedAt();
            if (createdAt == null) return;
            Duration latency = Duration.between(createdAt, publishedAt);
            if (latency.isNegative()) latency = Duration.ZERO;
            Tags tags = MetricTagPolicy.outboxPublish("ticket-service", event.getEventType(), outcome);
            Timer.builder(SeatFlowMetricNames.OUTBOX_PUBLISH_LATENCY).tags(tags).register(meterRegistry).record(latency);
        } catch (Exception ignored) {
        }
    }

    private void safeIncrementRetry(String eventType) {
        try {
            Tags tags = MetricTagPolicy.outboxRetry("ticket-service", eventType);
            Counter.builder(SeatFlowMetricNames.OUTBOX_RETRY_COUNT).tags(tags).register(meterRegistry).increment();
        } catch (Exception ignored) {
        }
    }

    private void safeIncrementDeadLetter(String eventType) {
        try {
            Tags tags = MetricTagPolicy.outboxDeadLetter("ticket-service", eventType);
            Counter.builder(SeatFlowMetricNames.OUTBOX_DEAD_LETTER).tags(tags).register(meterRegistry).increment();
        } catch (Exception ignored) {
        }
    }
}
