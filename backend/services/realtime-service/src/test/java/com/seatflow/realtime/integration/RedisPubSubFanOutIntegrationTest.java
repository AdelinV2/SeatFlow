package com.seatflow.realtime.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.realtime.config.RealtimeRedisProperties;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.redis.RedisSeatStatusSubscriber;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import com.seatflow.realtime.service.impl.RedisRealtimeFanOutPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
class RedisPubSubFanOutIntegrationTest {

    private static final String CHANNEL = "seatflow:realtime:seat-status:test";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private Subscriber firstSubscriber;
    private Subscriber secondSubscriber;
    private LettuceConnectionFactory publisherConnectionFactory;

    @AfterEach
    void closeRedisClients() {
        stop(firstSubscriber);
        stop(secondSubscriber);
        if (publisherConnectionFactory != null) {
            publisherConnectionFactory.destroy();
        }
    }

    @Test
    void deliversProductionFanOutToEverySubscriberAndRecoversAfterReconnect() {
        SeatStatusBroadcaster firstBroadcaster = mock(SeatStatusBroadcaster.class);
        SeatStatusBroadcaster secondBroadcaster = mock(SeatStatusBroadcaster.class);
        firstSubscriber = subscriber(firstBroadcaster);
        secondSubscriber = subscriber(secondBroadcaster);
        firstSubscriber.container().start();
        secondSubscriber.container().start();

        SeatStatusUpdateMessage firstPayload = payload(SeatStatus.HELD);
        publisher().publish("source-event-1", firstPayload);
        verify(firstBroadcaster, timeout(5_000)).broadcastSeatStatus(eq(firstPayload));
        verify(secondBroadcaster, timeout(5_000)).broadcastSeatStatus(eq(firstPayload));

        // Replaying the same logical Kafka event must not broadcast twice on either instance.
        publisher().publish("source-event-1", firstPayload);
        verify(firstBroadcaster, after(300).times(1)).broadcastSeatStatus(eq(firstPayload));
        verify(secondBroadcaster, after(300).times(1)).broadcastSeatStatus(eq(firstPayload));

        firstSubscriber.container().stop();
        SeatStatusUpdateMessage secondPayload = payload(SeatStatus.SOLD);
        publisher().publish("source-event-2", secondPayload);
        verify(secondBroadcaster, timeout(5_000)).broadcastSeatStatus(eq(secondPayload));
        verify(firstBroadcaster, after(300).never()).broadcastSeatStatus(eq(secondPayload));

        firstSubscriber.container().start();
        SeatStatusUpdateMessage thirdPayload = payload(SeatStatus.AVAILABLE);
        publisher().publish("source-event-3", thirdPayload);
        verify(firstBroadcaster, timeout(5_000)).broadcastSeatStatus(eq(thirdPayload));
        verify(secondBroadcaster, timeout(5_000)).broadcastSeatStatus(eq(thirdPayload));
        assertThat(firstSubscriber.container().isRunning()).isTrue();
    }

    private SeatStatusUpdateMessage payload(SeatStatus status) {
        return SeatStatusUpdateMessage.of(UUID.randomUUID(), List.of(UUID.randomUUID()), status,
                status == SeatStatus.HELD ? Instant.now().plusSeconds(900) : null);
    }

    private RedisRealtimeFanOutPublisher publisher() {
        if (publisherConnectionFactory == null) {
            publisherConnectionFactory = connectionFactory();
        }
        return new RedisRealtimeFanOutPublisher(new StringRedisTemplate(publisherConnectionFactory), objectMapper,
                new RealtimeRedisProperties(CHANNEL), "publisher", meterRegistry);
    }

    private Subscriber subscriber(SeatStatusBroadcaster broadcaster) {
        LettuceConnectionFactory connectionFactory = connectionFactory();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        RedisSeatStatusSubscriber subscriber = new RedisSeatStatusSubscriber(objectMapper, broadcaster, meterRegistry);
        container.addMessageListener(subscriber, new ChannelTopic(CHANNEL));
        container.afterPropertiesSet();
        return new Subscriber(container, connectionFactory);
    }

    private LettuceConnectionFactory connectionFactory() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static void stop(Subscriber subscriber) {
        if (subscriber != null) {
            try {
                subscriber.container().stop();
                subscriber.container().destroy();
                subscriber.connectionFactory().destroy();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to stop Redis test subscriber", exception);
            }
        }
    }

    private record Subscriber(RedisMessageListenerContainer container,
                              LettuceConnectionFactory connectionFactory) {
    }
}
