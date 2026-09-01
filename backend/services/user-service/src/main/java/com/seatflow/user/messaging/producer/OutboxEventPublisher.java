package com.seatflow.user.messaging.producer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatflow.common.events.EventHeaders;
import com.seatflow.common.events.EventTopics;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.user.model.entity.OutboxEvent;
import com.seatflow.user.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    private static final int SEND_TIMEOUT_SECONDS = 30;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final W3cTraceContextPropagator w3cTraceContextPropagator;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findUnpublishedForUpdate(MAX_RETRY_COUNT, batchSize);
        if (events.isEmpty()) {
            return;
        }

        log.debug("Outbox publisher polling. unpublishedCount={}", events.size());

        for (OutboxEvent event : events) {
            try {
                String payloadToSend = enrichPayloadWithTraceHeaders(event.getPayload());
                CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(
                        EventTopics.USER_EVENTS,
                        event.getAggregateId().toString(),
                        payloadToSend);
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                int updated = outboxEventRepository.markPublished(event.getId(), Instant.now());
                if (updated == 0) {
                    log.debug("Outbox event already published (possibly by another instance). outboxId={}", event.getId());
                } else {
                    log.info("Outbox event published. outboxId={}, eventType={}, aggregateId={}, topic={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), EventTopics.USER_EVENTS);
                }

            } catch (Exception ex) {
                int updated = outboxEventRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (updated == 0) {
                    log.error("Outbox delivery failed; exceeded max retry limit ({}). outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            MAX_RETRY_COUNT,
                            event.getId(), event.getEventType(), event.getAggregateId(), MAX_RETRY_COUNT, ex);
                } else {
                    log.error("Outbox delivery failed; retry incremented. outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount() + 1, ex);
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
            log.debug("Failed to enrich payload with trace headers; sending original payload. reason={}", ex.getClass().getSimpleName());
            return originalPayload;
        }
    }
}
