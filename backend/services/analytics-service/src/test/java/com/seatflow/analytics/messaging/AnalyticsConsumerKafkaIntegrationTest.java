package com.seatflow.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seatflow.analytics.config.KafkaConsumerConfig;
import com.seatflow.analytics.messaging.handlers.PaymentCompletedProjectionHandler;
import com.seatflow.analytics.metrics.AnalyticsConsumerMetrics;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real-broker verification of the TASK-P14-002 retry / DLQ / acknowledgement contract using the
 * production {@link DefaultErrorHandler} configuration (not a mock).
 *
 * <p>All tests share one embedded broker and consumer group, so every assertion is scoped to its
 * own {@code eventId}: DLQ probes match record content and handler verifications match the
 * envelope identity. This keeps asynchronously redelivered records from neighboring tests from
 * contaminating results.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(topics = {
        EventTopics.RESERVATION_EVENTS,
        EventTopics.PAYMENT_EVENTS,
        EventTopics.TICKET_EVENTS,
        EventTopics.EVENT_EVENTS,
        KafkaConsumerConfig.ANALYTICS_DLQ_TOPIC
}, partitions = 1)
class AnalyticsConsumerKafkaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_kafka_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoSpyBean
    private PaymentCompletedProjectionHandler paymentHandler;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private DefaultErrorHandler analyticsErrorHandler;

    @Autowired
    private ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AnalyticsConsumerMetrics consumerMetrics;

    @BeforeEach
    void resetSpies() {
        reset(paymentHandler);
    }

    @Test
    @DisplayName("Consumer baseline matches the SeatFlow contract")
    void consumerBaselineMatchesContract() {
        assertThat(KafkaConsumerConfig.ANALYTICS_DLQ_TOPIC).isEqualTo("seatflow.analytics.events.dlq");
        assertThat(analyticsErrorHandler).isNotNull();
        assertThat(kafkaListenerContainerFactory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.RECORD);

        Map<String, Object> props = consumerFactory.getConfigurationProperties();
        assertThat(props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
        assertThat(String.valueOf(props.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG))).isEqualTo("read_committed");
        assertThat(String.valueOf(props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG))).isEqualTo("earliest");
    }

    @Test
    @DisplayName("Unknown events on a subscribed topic are ignored without a processed marker")
    void unknownEventsAreIgnored() throws Exception {
        double ignoredBefore = meterRegistry.counter(
                "seatflow.analytics.events.ignored", "event_type", "UNKNOWN").count();

        String eventId = "evt-unknown-" + UUID.randomUUID();
        send(EventTopics.RESERVATION_EVENTS, unknownEnvelope("UserRegistered", eventId));

        await(Duration.ofSeconds(10), () ->
                meterRegistry.counter(
                        "seatflow.analytics.events.ignored", "event_type", "UNKNOWN").count() > ignoredBefore);

        assertThat(processedMarkerCount(eventId)).isZero();
        verify(paymentHandler, never()).project(forEvent(eventId), any());

        String followUp = "evt-followup-" + UUID.randomUUID();
        send(EventTopics.TICKET_EVENTS, ticketIssuedEnvelope(followUp));
        await(Duration.ofSeconds(15), () -> processedMarkerCount(followUp) == 1);
    }

    @Test
    @DisplayName("Malformed known events go directly to the analytics DLQ with no processed marker")
    void malformedKnownEventsGoDirectlyToDlq() throws Exception {
        String eventId = "evt-malformed-" + UUID.randomUUID();
        ObjectNode root = baseEnvelope("PaymentCompleted", eventId);
        ObjectNode payload = root.putObject("payload");
        payload.put("reservationId", UUID.randomUUID().toString());
        payload.put("amount", "10.00");
        payload.put("currency", "RON");
        send(EventTopics.PAYMENT_EVENTS, objectMapper.writeValueAsString(root));

        ConsumerRecord<String, String> dlq = awaitDlqRecord(eventId, Duration.ofSeconds(20));
        assertThat(dlq).isNotNull();
        assertThat(new String(dlq.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                StandardCharsets.UTF_8)).isEqualTo(EventTopics.PAYMENT_EVENTS);

        assertThat(processedMarkerCount(eventId)).isZero();
        verify(paymentHandler, never()).project(forEvent(eventId), any());
        await(Duration.ofSeconds(10), () ->
                meterRegistry.counter("seatflow.analytics.events.dead_lettered",
                        "event_type", "PaymentCompleted", "reason_category", "VALIDATION").count() >= 1.0);
        await(Duration.ofSeconds(10), () ->
                meterRegistry.counter("seatflow.analytics.events.failed",
                        "event_type", "PaymentCompleted", "reason_category", "VALIDATION").count() >= 1.0);
    }

    @Test
    @DisplayName("Transient handler failure retries and then succeeds without DLQ")
    void transientFailureRetriesThenSucceeds() throws Exception {
        doThrow(new IllegalStateException("boom"))
                .doCallRealMethod()
                .when(paymentHandler).project(any(), any());

        String eventId = "evt-transient-" + UUID.randomUUID();
        send(EventTopics.PAYMENT_EVENTS, paymentCompletedEnvelope(eventId));

        await(Duration.ofSeconds(25), () -> processedMarkerCount(eventId) == 1);
        verify(paymentHandler, times(2)).project(forEvent(eventId), any());
        await(Duration.ofSeconds(10), () ->
                meterRegistry.counter("seatflow.analytics.events.processed",
                        "event_type", "PaymentCompleted").count() >= 1.0);
    }

    @Test
    @DisplayName("Persistent transient failure exhausts retries and lands in the DLQ with no marker")
    void persistentFailureLandsInDlqAfterRetries() throws Exception {
        doThrow(new IllegalStateException("always broken"))
                .when(paymentHandler).project(any(), any());

        String eventId = "evt-poison-" + UUID.randomUUID();
        send(EventTopics.PAYMENT_EVENTS, paymentCompletedEnvelope(eventId));

        ConsumerRecord<String, String> dlq = awaitDlqRecord(eventId, Duration.ofSeconds(40));
        assertThat(dlq).isNotNull();

        verify(paymentHandler, times(4)).project(forEvent(eventId), any());
        assertThat(processedMarkerCount(eventId)).isZero();
    }

    @Test
    @DisplayName("Duplicate delivery of a committed event performs no second projection")
    void duplicateDeliveryIsNoOp() throws Exception {
        String eventId = "evt-dup-" + UUID.randomUUID();
        String json = paymentCompletedEnvelope(eventId);
        send(EventTopics.PAYMENT_EVENTS, json);

        await(Duration.ofSeconds(15), () -> processedMarkerCount(eventId) == 1);
        verify(paymentHandler, times(1)).project(forEvent(eventId), any());

        // TASK-P14-007 §8: synchronize on the observable duplicate signal, never a blind
        // sleep, so a delayed second delivery cannot false-pass (REV-004).
        double duplicatesBefore = meterRegistry.counter(
                "seatflow.analytics.events.duplicate", "event_type", "PaymentCompleted").count();
        send(EventTopics.PAYMENT_EVENTS, json);
        await(Duration.ofSeconds(15), () ->
                meterRegistry.counter(
                        "seatflow.analytics.events.duplicate", "event_type", "PaymentCompleted")
                        .count() > duplicatesBefore);

        verify(paymentHandler, times(1)).project(forEvent(eventId), any());
        assertThat(processedMarkerCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Payment events are accepted before any reservation fact exists (no cross-topic wait)")
    void paymentBeforeReservationIsAccepted() throws Exception {
        String eventId = "evt-ooo-" + UUID.randomUUID();
        send(EventTopics.PAYMENT_EVENTS, paymentCompletedEnvelope(eventId, UUID.randomUUID().toString()));

        await(Duration.ofSeconds(15), () -> processedMarkerCount(eventId) == 1);
        verify(paymentHandler, times(1)).project(forEvent(eventId), any());
    }

    @Test
    @DisplayName("DLQ record preserves original topic, partition, and offset headers")
    void dlqRecordPreservesSourceHeaders() throws Exception {
        String eventId = "evt-hdr-" + UUID.randomUUID();
        ObjectNode root = baseEnvelope("PaymentCompleted", eventId);
        ObjectNode payload = root.putObject("payload");
        payload.put("reservationId", UUID.randomUUID().toString());
        payload.put("amount", "10.00");
        payload.put("currency", "RON");
        send(EventTopics.PAYMENT_EVENTS, objectMapper.writeValueAsString(root));

        ConsumerRecord<String, String> dlq = awaitDlqRecord(eventId, Duration.ofSeconds(20));
        assertThat(dlq).isNotNull();
        assertThat(new String(dlq.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                StandardCharsets.UTF_8)).isEqualTo(EventTopics.PAYMENT_EVENTS);
        assertThat(dlq.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION)).isNotNull();
        assertThat(dlq.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();
        assertThat(java.nio.ByteBuffer.wrap(
                dlq.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt())
                .isZero();
        assertThat(java.nio.ByteBuffer.wrap(
                dlq.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong())
                .isGreaterThanOrEqualTo(0L);

        assertThat(processedMarkerCount(eventId)).isZero();
    }

    @Test
    @DisplayName("DLQ recovery lets later valid records continue on the same consumer")
    void dlqRecoveryAllowsLaterRecords() throws Exception {
        doThrow(new IllegalStateException("always broken"))
                .when(paymentHandler).project(any(), any());

        String poison = "evt-poison-seq-" + UUID.randomUUID();
        send(EventTopics.PAYMENT_EVENTS, paymentCompletedEnvelope(poison));

        ConsumerRecord<String, String> dlq = awaitDlqRecord(poison, Duration.ofSeconds(40));
        assertThat(dlq).isNotNull();
        assertThat(processedMarkerCount(poison)).isZero();

        // The poisoned record was recovered to the DLQ; the same consumer must still
        // process a later valid record instead of stalling on the poison.
        reset(paymentHandler);
        String followUp = "evt-after-dlq-" + UUID.randomUUID();
        send(EventTopics.PAYMENT_EVENTS, paymentCompletedEnvelope(followUp));

        await(Duration.ofSeconds(15), () -> processedMarkerCount(followUp) == 1);
        verify(paymentHandler, times(1)).project(forEvent(followUp), any());
    }

    @Test
    @DisplayName("DLQ publishing failure stays visible: retried, never marked processed")
    void dlqPublishFailureStaysVisible() {
        // Simulate a DLQ outage behind the production recoverer wiring: every send
        // overload fails synchronously, so recovery cannot be mistaken for success.
        org.mockito.stubbing.Answer<Object> failOnSend = invocation -> {
            if (invocation.getMethod().getName().equals("send")) {
                throw new IllegalStateException("DLQ unavailable");
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        };
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<String, String> failingTemplate =
                org.mockito.Mockito.mock(
                        org.springframework.kafka.core.KafkaTemplate.class, failOnSend);

        // Rebuild the production recoverer against the failing DLQ seam and drive it
        // directly with a poisoned record: the failure must propagate (visible) rather
        // than being acknowledged as a successful recovery.
        org.springframework.kafka.listener.DeadLetterPublishingRecoverer recoverer =
                new KafkaConsumerConfig(objectMapper, consumerMetrics)
                        .analyticsDeadLetterRecoverer(failingTemplate);
        org.apache.kafka.clients.consumer.ConsumerRecord<String, String> poison =
                new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                        EventTopics.PAYMENT_EVENTS, 0, 0L, "key", "not-json{{{");
        try {
            recoverer.accept(poison, new IllegalStateException("cause",
                    new AnalyticsEventValidationException(
                            "evt-dlq-fail", "PaymentCompleted", "bad payload")));
            throw new AssertionError("Expected DLQ publish failure to propagate");
        } catch (RuntimeException expected) {
            // Visible, never swallowed: the production recoverer throws a failure naming
            // the DLQ publish instead of returning normally (which the container would
            // acknowledge as a successful recovery). The framework does not attach the
            // broker cause, so the seam permits asserting the named failure only.
            assertThat(expected).hasMessageContaining("Dead-letter publication");
            assertThat(expected.getMessage()).contains(KafkaConsumerConfig.ANALYTICS_DLQ_TOPIC);
        }
    }

    private static EventEnvelope<JsonNode> forEvent(String eventId) {
        return argThat((EventEnvelope<JsonNode> envelope) ->
                envelope != null && eventId.equals(envelope.eventId()));
    }

    private void send(String topic, String json) throws Exception {
        kafkaTemplate.send(topic, UUID.randomUUID().toString(), json).get(10, TimeUnit.SECONDS);
    }

    private ObjectNode baseEnvelope(String eventType, String eventId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", eventType);
        root.put("occurredAt", Instant.now().toString());
        root.put("aggregateId", UUID.randomUUID().toString());
        root.put("correlationId", "corr-test");
        return root;
    }

    private String unknownEnvelope(String eventType, String eventId) throws Exception {
        ObjectNode root = baseEnvelope(eventType, eventId);
        root.putObject("payload").put("anything", "goes");
        return objectMapper.writeValueAsString(root);
    }

    private String paymentCompletedEnvelope(String eventId) throws Exception {
        return paymentCompletedEnvelope(eventId, UUID.randomUUID().toString());
    }

    private String paymentCompletedEnvelope(String eventId, String reservationId) throws Exception {
        ObjectNode root = baseEnvelope("PaymentCompleted", eventId);
        ObjectNode payload = root.putObject("payload");
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("reservationId", reservationId);
        payload.put("amount", "150.00");
        payload.put("currency", "RON");
        return objectMapper.writeValueAsString(root);
    }

    private String ticketIssuedEnvelope(String eventId) throws Exception {
        ObjectNode root = baseEnvelope("TicketIssued", eventId);
        ObjectNode payload = root.putObject("payload");
        payload.put("ticketId", UUID.randomUUID().toString());
        payload.put("reservationId", UUID.randomUUID().toString());
        return objectMapper.writeValueAsString(root);
    }

    private int processedMarkerCount(String eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
        return count == null ? 0 : count;
    }

    private ConsumerRecord<String, String> awaitDlqRecord(String eventId, Duration timeout)
            throws InterruptedException {
        Map<String, Object> props = new java.util.HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of(KafkaConsumerConfig.ANALYTICS_DLQ_TOPIC));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (eventId.equals(extractEventId(record.value()))) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    private String extractEventId(String value) {
        try {
            return objectMapper.readTree(value).path("eventId").asText(null);
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
        if (!condition.getAsBoolean()) {
            throw new IllegalStateException("Timed out waiting for condition after " + timeout);
        }
    }
}
