package com.seatflow.payment.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

    @Value("${outbox.publisher.topic:" + EventTopics.PAYMENT_EVENTS + "}")
    private String topic = EventTopics.PAYMENT_EVENTS;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findUnpublishedForUpdate(MAX_RETRY_COUNT, batchSize);
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found unpublished payment outbox events to publish. count={}", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(
                        topic,
                        event.getAggregateId().toString(),
                        event.getPayload()
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                int updated = outboxEventRepository.markPublished(event.getId(), Instant.now());
                if (updated > 0) {
                    log.info("Payment outbox event published successfully. outboxEventId={}, aggregateId={}, eventType={}, topic={}",
                            event.getId(), event.getAggregateId(), event.getEventType(), topic);
                }
            } catch (Exception ex) {
                int retryUpdated = outboxEventRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (retryUpdated == 0) {
                    log.error("Payment outbox event exceeded max retry limit ({}) or was already published. outboxEventId={}, eventType={}, aggregateId={}",
                            MAX_RETRY_COUNT, event.getId(), event.getEventType(), event.getAggregateId(), ex);
                } else {
                    log.warn("Failed to publish payment outbox event, retry incremented. outboxEventId={}, eventType={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getRetryCount() + 1, ex);
                }
            }
        }
    }
}
