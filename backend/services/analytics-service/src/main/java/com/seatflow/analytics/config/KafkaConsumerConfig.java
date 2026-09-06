package com.seatflow.analytics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.analytics.messaging.AnalyticsEventValidationException;
import com.seatflow.analytics.metrics.AnalyticsConsumerMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer infrastructure for the analytics read model (TASK-P14-002).
 *
 * <p>Matches SeatFlow's existing consumer convention (reservation-service):
 * {@code StringDeserializer} key/value with manual canonical {@code EventEnvelope} parsing,
 * stable {@code analytics-service-v1} group, {@code earliest} offset reset, disabled auto-commit,
 * {@code read_committed} isolation, and RECORD acknowledgement. The offset may advance only
 * after the analytics DB transaction commits or after successful DLQ recovery.
 *
 * <p>Retry policy: transient failures get {@code 1000ms x 3} retries, then the analytics DLQ.
 * {@link AnalyticsEventValidationException} is non-retryable and recovers directly to the DLQ.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    /**
     * Analytics-specific dead-letter topic. Single constant — never scatter the literal.
     */
    public static final String ANALYTICS_DLQ_TOPIC = "seatflow.analytics.events.dlq";

    static final long FIXED_BACKOFF_INTERVAL_MS = 1000L;
    static final long FIXED_BACKOFF_MAX_RETRIES = 3L;

    private final ObjectMapper objectMapper;
    private final AnalyticsConsumerMetrics consumerMetrics;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:analytics-service-v1}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public DeadLetterPublishingRecoverer analyticsDeadLetterRecoverer(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (consumerRecord, ex) -> new TopicPartition(ANALYTICS_DLQ_TOPIC, consumerRecord.partition()));
        recoverer.setHeadersFunction((consumerRecord, ex) -> {
            consumerMetrics.incrementDeadLettered(extractEventType(consumerRecord.value()), classifyReason(ex));
            return new org.apache.kafka.common.header.internals.RecordHeaders();
        });
        return recoverer;
    }

    @Bean
    public DefaultErrorHandler analyticsErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(FIXED_BACKOFF_INTERVAL_MS, FIXED_BACKOFF_MAX_RETRIES));
        errorHandler.addNotRetryableExceptions(AnalyticsEventValidationException.class);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Retrying analytics-service consumer record. attempt={}, topic={}, eventType={}",
                        deliveryAttempt, record.topic(), extractEventType(record.value())));
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler analyticsErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(analyticsErrorHandler);
        return factory;
    }

    private String extractEventType(Object value) {
        try {
            if (value instanceof String raw) {
                return objectMapper.readTree(raw).path("eventType").asText(null);
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // Best-effort label only; never fail DLQ recovery on label extraction.
        }
        return null;
    }

    /**
     * Spring Kafka delivers listener failures to the recoverer wrapped (e.g. in
     * {@code ListenerExecutionFailedException}), so the cause chain must be inspected to
     * distinguish non-retryable validation poison from transient infrastructure failures.
     */
    private static String classifyReason(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof AnalyticsEventValidationException) {
                return "VALIDATION";
            }
            current = current.getCause();
        }
        return "TRANSIENT";
    }
}
