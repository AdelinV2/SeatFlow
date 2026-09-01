package com.seatflow.realtime.config;

import com.seatflow.realtime.messaging.redis.RedisSeatStatusSubscriber;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.ExponentialBackOff;

@Slf4j
@Configuration
@EnableConfigurationProperties(RealtimeRedisProperties.class)
public class RedisPubSubConfig {

    @Bean("realtimeRedisMessageExecutor")
    public TaskExecutor realtimeRedisMessageExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("realtime-redis-message-");
        Counter droppedCounter = Counter.builder("seatflow.realtime.redis.dropped")
                .description("Redis realtime messages dropped when the local broadcast executor is saturated")
                .register(meterRegistry);
        executor.setRejectedExecutionHandler((runnable, threadPoolExecutor) -> {
            droppedCounter.increment();
            log.warn("Dropping stale realtime Redis update because the broadcast executor is saturated");
        });
        executor.initialize();
        return executor;
    }

    @Bean("realtimeRedisSubscriptionExecutor")
    public TaskExecutor realtimeRedisSubscriptionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("realtime-redis-subscription-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Topic realtimeRedisTopic(RealtimeRedisProperties properties) {
        return new ChannelTopic(properties.channel());
    }

    @Bean
    @ConditionalOnProperty(
            name = "seatflow.realtime.redis.listener.enabled",
            havingValue = "true",
            matchIfMissing = true)
    @DependsOn({"realtimeRedisMessageExecutor", "realtimeRedisSubscriptionExecutor"})
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisSeatStatusSubscriber subscriber,
            Topic realtimeRedisTopic,
            TaskExecutor realtimeRedisMessageExecutor,
            TaskExecutor realtimeRedisSubscriptionExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(realtimeRedisMessageExecutor);
        container.setSubscriptionExecutor(realtimeRedisSubscriptionExecutor);
        ExponentialBackOff recovery = new ExponentialBackOff(1_000L, 2.0);
        recovery.setMaxInterval(30_000L);
        container.setRecoveryBackoff(recovery);
        container.setErrorHandler(error -> log.error("Redis Pub/Sub listener error", error));
        container.addMessageListener(subscriber, realtimeRedisTopic);
        return container;
    }
}
