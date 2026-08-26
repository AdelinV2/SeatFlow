package com.seatflow.payment.messaging.producer;

import com.seatflow.common.events.EventTopics;
import com.seatflow.payment.model.entity.OutboxEvent;
import com.seatflow.payment.repository.OutboxEventRepository;
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

    @Value("${outbox.publisher.topic:" + EventTopics.PAYMENT_EVENTS + "}")
    private String topic = EventTopics.PAYMENT_EVENTS;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms:3000}")
    public void publishPendingEvents() {
        // Claim a batch in its own short transaction (FOR UPDATE SKIP LOCKED) and release the
        // acquired row locks immediately. The blocking Kafka send must NOT happen inside a database
        // transaction, otherwise a slow/hanging broker would hold PostgreSQL row locks and exhaust
        // the connection pool (risk of lock_timeout / idle_in_transaction_session_timeout).
        List<OutboxEvent> pendingEvents = claimPendingEvents();
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.debug("Found unpublished payment outbox events to publish. count={}", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                CompletableFuture<SendResult<String, String>> sendFuture = kafkaTemplate.send(
                        topic,
                        event.getAggregateId().toString(),
                        event.getPayload().toString()
                );
                sendFuture.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                int updated = outboxEventRepository.markPublished(event.getId(), Instant.now());
                if (updated > 0) {
                    log.info("Payment outbox event published successfully. outboxEventId={}, aggregateId={}, eventType={}, topic={}",
                            event.getId(), event.getAggregateId(), event.getEventType(), topic);
                }
            } catch (Exception ex) {
                int updated = outboxEventRepository.incrementRetryCount(event.getId(), MAX_RETRY_COUNT);
                if (updated == 0) {
                    log.error("Payment outbox event exceeded max retry limit ({}) or was already published. outboxEventId={}, eventType={}, aggregateId={}",
                            MAX_RETRY_COUNT, event.getId(), event.getEventType(), event.getAggregateId(), ex);
                    meterRegistry.counter("seatflow.outbox.dead.letter.total", "eventType", event.getEventType()).increment();
                } else {
                    log.warn("Failed to publish payment outbox event, retry incremented. outboxEventId={}, eventType={}, aggregateId={}",
                            event.getId(), event.getEventType(), event.getAggregateId(), ex);
                }
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimPendingEvents() {
        return outboxEventRepository.findUnpublishedForUpdate(MAX_RETRY_COUNT, batchSize);
    }
}
