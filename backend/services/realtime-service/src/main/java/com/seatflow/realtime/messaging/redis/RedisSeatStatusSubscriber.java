package com.seatflow.realtime.messaging.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.realtime.dto.RedisSeatStatusEnvelope;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RedisSeatStatusSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SeatStatusBroadcaster seatStatusBroadcaster;
    private final Counter receivedCounter;
    private final Counter errorCounter;
    private final Counter duplicateCounter;
    private final StringRedisSerializer serializer = new StringRedisSerializer();
    private final ConcurrentHashMap<String, Long> deliveredSourceEvents = new ConcurrentHashMap<>();
    private static final long DEDUPLICATION_WINDOW_MILLIS = 10 * 60 * 1_000L;
    private static final int MAX_TRACKED_SOURCE_EVENTS = 100_000;

    public RedisSeatStatusSubscriber(
            ObjectMapper objectMapper,
            SeatStatusBroadcaster seatStatusBroadcaster,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.seatStatusBroadcaster = seatStatusBroadcaster;
        this.receivedCounter = Counter.builder("seatflow.realtime.redis.received").register(meterRegistry);
        this.errorCounter = Counter.builder("seatflow.realtime.redis.consume.errors").register(meterRegistry);
        this.duplicateCounter = Counter.builder("seatflow.realtime.redis.duplicates").register(meterRegistry);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String sourceEventId = null;
        try {
            String body = serializer.deserialize(message.getBody());
            RedisSeatStatusEnvelope envelope = objectMapper.readValue(body, RedisSeatStatusEnvelope.class);
            validate(envelope);
            sourceEventId = envelope.sourceEventId();
            if (isDuplicate(sourceEventId)) {
                duplicateCounter.increment();
                log.debug("Discarding duplicate realtime Redis update: sourceEventId={}", sourceEventId);
                return;
            }
            seatStatusBroadcaster.broadcastSeatStatus(envelope.payload());
            receivedCounter.increment();
            SeatStatusUpdateMessage payload = envelope.payload();
            log.info("Consumed realtime Redis update: sourceEventId={}, messageId={}, originInstanceId={}, eventId={}, status={}, seatCount={}",
                    envelope.sourceEventId(), envelope.messageId(), envelope.originInstanceId(), payload.eventId(),
                    payload.status(), payload.seatIds().size());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException exception) {
            errorCounter.increment();
            log.warn("Discarding malformed realtime Redis update: {}", exception.getMessage());
        } catch (Exception exception) {
            if (sourceEventId != null) {
                deliveredSourceEvents.remove(sourceEventId);
            }
            errorCounter.increment();
            log.error("Realtime Redis update could not be broadcast", exception);
        }
    }

    private static void validate(RedisSeatStatusEnvelope envelope) {
        if (envelope == null || !StringUtils.hasText(envelope.sourceEventId())
                || envelope.messageId() == null || !StringUtils.hasText(envelope.originInstanceId())
                || envelope.publishedAt() == null || envelope.payload() == null
                || envelope.payload().eventId() == null || envelope.payload().status() == null
                || envelope.payload().seatIds() == null || envelope.payload().seatIds().isEmpty()
                || envelope.payload().seatIds().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Redis realtime envelope is missing required fields");
        }
    }

    private boolean isDuplicate(String sourceEventId) {
        long now = System.currentTimeMillis();
        Long previous = deliveredSourceEvents.putIfAbsent(sourceEventId, now);
        if (previous != null && now - previous < DEDUPLICATION_WINDOW_MILLIS) {
            return true;
        }
        if (previous != null) {
            deliveredSourceEvents.replace(sourceEventId, previous, now);
        }
        if (deliveredSourceEvents.size() > MAX_TRACKED_SOURCE_EVENTS) {
            deliveredSourceEvents.entrySet().stream().findAny()
                    .ifPresent(entry -> deliveredSourceEvents.remove(entry.getKey(), entry.getValue()));
        }
        return false;
    }
}
