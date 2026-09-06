package com.seatflow.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatflow.analytics.repository.ProcessedEventRepository;
import com.seatflow.common.events.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic idempotency claim + projection execution.
 *
 * <p>Contract: claim {@code EventEnvelope.eventId} with a single
 * {@code INSERT ... ON CONFLICT DO NOTHING} and run exactly one projection handler inside the
 * same local analytics transaction. Affected rows {@code 1} owns the event; {@code 0} is a
 * duplicate redelivery and performs no mutation. Any handler failure rolls back the claim so a
 * retry can own the event. The Kafka RECORD offset may advance only after this transaction
 * commits (the listener returns normally) or after explicit DLQ recovery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectionEventProcessor {

    public enum Outcome {
        PROCESSED,
        DUPLICATE,
        IGNORED
    }

    private final ProcessedEventRepository processedEventRepository;
    private final AnalyticsEventDispatcher dispatcher;

    @Transactional
    public Outcome process(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
        ProjectionHandler handler = dispatcher.handlerFor(envelope.eventType()).orElse(null);
        if (handler == null) {
            log.debug("Ignoring non-analytics event. eventType={}, eventId={}, topic={}",
                    envelope.eventType(), envelope.eventId(), metadata.topic());
            return Outcome.IGNORED;
        }
        int claimed = processedEventRepository.claim(
                envelope.eventId(),
                envelope.eventType(),
                metadata.topic(),
                metadata.partition(),
                metadata.offset(),
                envelope.occurredAt());
        if (claimed == 0) {
            log.info("Duplicate analytics event ignored. eventType={}, eventId={}, topic={}, partition={}, offset={}",
                    envelope.eventType(), envelope.eventId(),
                    metadata.topic(), metadata.partition(), metadata.offset());
            return Outcome.DUPLICATE;
        }
        handler.project(envelope, metadata);
        log.info("Analytics event projected. eventType={}, eventId={}, topic={}, partition={}, offset={}",
                envelope.eventType(), envelope.eventId(),
                metadata.topic(), metadata.partition(), metadata.offset());
        return Outcome.PROCESSED;
    }
}
