package com.seatflow.realtime.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.realtime.config.RealtimeRedisProperties;
import com.seatflow.realtime.dto.RedisSeatStatusEnvelope;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.service.RealtimeFanOutPublisher;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class RedisRealtimeFanOutPublisher implements RealtimeFanOutPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RealtimeRedisProperties properties;
    private final String instanceId;
    private final Counter publishedCounter;
    private final Counter errorCounter;

    public RedisRealtimeFanOutPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RealtimeRedisProperties properties,
            @Value("${seatflow.realtime.instance-id:${spring.application.name}:${random.uuid}}") String instanceId,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.instanceId = instanceId;
        this.publishedCounter = Counter.builder("seatflow.realtime.redis.published").register(meterRegistry);
        this.errorCounter = Counter.builder("seatflow.realtime.redis.publish.errors").register(meterRegistry);
    }

    @Override
    public void publish(String sourceEventId, SeatStatusUpdateMessage payload) {
        if (!StringUtils.hasText(sourceEventId) || payload == null) {
            throw new IllegalArgumentException("sourceEventId and payload are required for Redis fan-out");
        }
        try {
            RedisSeatStatusEnvelope envelope = new RedisSeatStatusEnvelope(
                    sourceEventId, UUID.randomUUID(), instanceId, Instant.now(), payload);
            String body = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(properties.channel(), body);
            publishedCounter.increment();
            log.info("Published realtime Redis update: sourceEventId={}, messageId={}, eventId={}, status={}, seatCount={}",
                    sourceEventId, envelope.messageId(), payload.eventId(), payload.status(), payload.seatIds().size());
        } catch (JsonProcessingException | RuntimeException exception) {
            errorCounter.increment();
            throw new IllegalStateException("Failed to publish realtime update to Redis", exception);
        }
    }
}
