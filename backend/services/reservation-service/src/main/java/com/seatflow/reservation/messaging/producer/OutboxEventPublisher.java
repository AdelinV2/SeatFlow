package com.seatflow.reservation.messaging.producer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatflow.common.events.EventHeaders;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.metrics.MetricTagPolicy;
import com.seatflow.common.observability.metrics.AfterCommitMetrics;
import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.reservation.model.entity.OutboxEvent;
import com.seatflow.reservation.repository.OutboxEventRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {

    private static final int MAX_RETRY_COUNT = 5;
    private static final int SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final W3cTraceContextPropagator w3cTraceContextPropagator;

    @Value("${outbox.publisher.topic:" + EventTopics.RESERVATION_EVENTS + "}")
    private String topic = EventTopics.RESERVATION_EVENTS;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findUnpublishedForUpdate(MAX_RETRY_COUNT, batchSize);
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found unpublished outbox events to publish. count={}", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                String payloadToSend = enrichPayloadWithTraceHeaders(event.getPayload());
                CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(
                        topic,
                        event.getAggregateId().toString(),
                        payloadToSend
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                Instant publishedAt = Instant.now();
                int updated = outboxEventRepository.markPublished(event.getId(), publishedAt);
                if (updated > 0) {
                    log.info("Outbox event published successfully. outboxId={}, aggregateId={}, eventType={}, topic={}",
                            event.getId(), event.getAggregateId(), event.getEventType(), topic);
                    AfterCommitMetrics.afterCommit(() -> safeRecordOutboxLatency(event, publishedAt, "SUCCESS"));
                } else {
                    log.warn("Outbox acknowledgement was not persisted; success metric suppressed. outboxId={}",
                            event.getId());
                }
            } catch (Exception ex) {
                int retryUpdated = outboxEventRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (retryUpdated == 0) {
                    log.error("Outbox delivery failed; exceeded max retry limit ({}). outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            MAX_RETRY_COUNT, event.getId(), event.getEventType(), event.getAggregateId(),
                            MAX_RETRY_COUNT, ex);
                    Instant failedAt = Instant.now();
                    AfterCommitMetrics.afterCommit(() -> {
                        safeIncrementDeadLetter(event.getEventType());
                        safeRecordOutboxLatency(event, failedAt, "FAILED");
                    });
                } else {
                    log.error("Outbox delivery failed; retry incremented. outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount() + 1, ex);
                    AfterCommitMetrics.afterCommit(() -> safeIncrementRetry(event.getEventType()));
                }
            }
        }
    }

    private String enrichPayloadWithTraceHeaders(String originalPayload) {
        if (originalPayload == null || originalPayload.isBlank()) {
            return originalPayload;
        }
        try {
            JsonNode root = objectMapper.readTree(originalPayload);
            if (!root.isObject()) {
                return originalPayload;
            }
            ObjectNode objectNode = (ObjectNode) root;
            JsonNode headersNode = objectNode.get("headers");
            boolean hasTraceParent = headersNode != null && headersNode.has(EventHeaders.TRACEPARENT);
            if (hasTraceParent) {
                return originalPayload;
            }
            Map<String, String> tempHeaders = new HashMap<>();
            if (w3cTraceContextPropagator != null) {
                try {
                    w3cTraceContextPropagator.inject(tempHeaders);
                } catch (Exception ignored) {
                }
            }
            if (tempHeaders.isEmpty()) {
                return originalPayload;
            }
            ObjectNode headersObject;
            if (headersNode != null && headersNode.isObject()) {
                headersObject = (ObjectNode) headersNode;
            } else {
                headersObject = objectMapper.createObjectNode();
                objectNode.set("headers", headersObject);
            }
            tempHeaders.forEach(headersObject::put);
            return objectMapper.writeValueAsString(objectNode);
        } catch (Exception ex) {
            log.debug("Failed to enrich reservation payload with trace headers; sending original. reason={}", ex.getClass().getSimpleName());
            return originalPayload;
        }
    }

    private void safeRecordOutboxLatency(OutboxEvent event, Instant publishedAt, String outcome) {
        try {
            Instant createdAt = event.getCreatedAt();
            if (createdAt == null) return;
            Duration latency = Duration.between(createdAt, publishedAt);
            if (latency.isNegative()) latency = Duration.ZERO;
            Tags tags = MetricTagPolicy.outboxPublish("reservation-service", event.getEventType(), outcome);
            Timer.builder(SeatFlowMetricNames.OUTBOX_PUBLISH_LATENCY)
                    .tags(tags)
                    .register(meterRegistry)
                    .record(latency);
        } catch (Exception ignored) {
        }
    }

    private void safeIncrementRetry(String eventType) {
        try {
            Tags tags = MetricTagPolicy.outboxRetry("reservation-service", eventType);
            Counter.builder(SeatFlowMetricNames.OUTBOX_RETRY_COUNT).tags(tags).register(meterRegistry).increment();
        } catch (Exception ignored) {
        }
    }

    private void safeIncrementDeadLetter(String eventType) {
        try {
            Tags tags = MetricTagPolicy.outboxDeadLetter("reservation-service", eventType);
            Counter.builder(SeatFlowMetricNames.OUTBOX_DEAD_LETTER).tags(tags).register(meterRegistry).increment();
        } catch (Exception ignored) {
        }
    }
}
