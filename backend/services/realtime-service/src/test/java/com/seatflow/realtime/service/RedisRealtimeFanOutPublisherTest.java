package com.seatflow.realtime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.realtime.config.RealtimeRedisProperties;
import com.seatflow.realtime.dto.RedisSeatStatusEnvelope;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.service.impl.RedisRealtimeFanOutPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRealtimeFanOutPublisherTest {

    @Test
    void publishesAnEnvelopeWithSourceEventAndUniqueDeliveryId() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(1L);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RedisRealtimeFanOutPublisher publisher = new RedisRealtimeFanOutPublisher(
                redisTemplate,
                objectMapper,
                new RealtimeRedisProperties("seatflow:realtime:seat-status"),
                "instance-a",
                new SimpleMeterRegistry());
        SeatStatusUpdateMessage payload = SeatStatusUpdateMessage.of(
                UUID.randomUUID(), List.of(UUID.randomUUID()), SeatStatus.SOLD, null);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);

        publisher.publish("event-envelope-1", payload);

        verify(redisTemplate).convertAndSend(eq("seatflow:realtime:seat-status"), bodyCaptor.capture());
        RedisSeatStatusEnvelope published = objectMapper.readValue(bodyCaptor.getValue(), RedisSeatStatusEnvelope.class);
        assertThat(published.sourceEventId()).isEqualTo("event-envelope-1");
        assertThat(published.messageId()).isNotNull();
        assertThat(published.originInstanceId()).isEqualTo("instance-a");
        assertThat(published.payload()).isEqualTo(payload);
    }
}
