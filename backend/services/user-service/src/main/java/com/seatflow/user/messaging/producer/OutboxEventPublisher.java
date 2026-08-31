package com.seatflow.user.messaging.producer;

import com.seatflow.common.events.EventTopics;
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
import java.util.List;
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
                CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(
                        EventTopics.USER_EVENTS,
                        event.getAggregateId().toString(),
                        event.getPayload());
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
                    log.error("Outbox delivery failed; exceeded max retry limit ({}). outboxId={}, eventType={}, aggregateId={}",
                            MAX_RETRY_COUNT,
                            event.getId(), event.getEventType(), event.getAggregateId(), ex);
                } else {
                    log.error("Outbox delivery failed; retry incremented. outboxId={}, eventType={}, aggregateId={}, retryCount={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount() + 1, ex);
                }
            }
        }
    }
}
