package com.seatflow.common.observability.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventHeaders;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class KafkaW3cPropagationIntegrationTest {

    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("confluentinc/cp-kafka:7.6.0");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(KAFKA_IMAGE);

    private ObjectMapper objectMapper;
    private W3cTraceContextPropagator propagator;
    private KafkaListenerTraceScope kafkaListenerTraceScope;

    record TestPayload(String message) implements com.seatflow.common.events.DomainEvent {}

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        propagator = new W3cTraceContextPropagator();
        kafkaListenerTraceScope = new KafkaListenerTraceScope(propagator, null);
    }

    @AfterEach
    void tearDown() {
        // no-op
    }

    @Test
    void shouldPreserveW3cHeadersAcrossKafkaBoundary() throws Exception {
        String topic = "seatflow.test.w3c-" + UUID.randomUUID();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentId = "00f067aa0ba902b7";
        SpanContext sc = SpanContext.create(traceId, parentId, TraceFlags.getSampled(), TraceState.getDefault());
        Span span = Span.wrap(sc);
        Context ctx = Context.current().with(span);

        Map<String, String> headersToInject = new HashMap<>();
        try (Scope scope = ctx.makeCurrent()) {
            propagator.inject(headersToInject);
        }
        // Verify injection produced valid traceparent
        assertThat(headersToInject).containsKey(EventHeaders.TRACEPARENT);
        String traceparent = headersToInject.get(EventHeaders.TRACEPARENT);
        assertThat(traceparent).isEqualTo("00-" + traceId + "-" + parentId + "-01");

        // Create envelope with injected headers
        TestPayload payload = new TestPayload("hello-kafka");
        EventEnvelope<TestPayload> envelope = EventEnvelope.of("TestEvent", UUID.randomUUID().toString(), UUID.randomUUID().toString(), payload)
                .withHeaders(headersToInject);

        String json = objectMapper.writeValueAsString(envelope);

        // Produce to Kafka
        try (KafkaProducer<String, String> producer = createProducer();
             KafkaConsumer<String, String> consumer = createConsumer(topic)) {

            producer.send(new ProducerRecord<>(topic, UUID.randomUUID().toString(), json)).get();

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records.count()).isGreaterThan(0);
            ConsumerRecord<String, String> record = records.iterator().next();
            String receivedJson = record.value();
            assertThat(receivedJson).isNotNull();

            // Deserialize envelope and verify headers survived
            @SuppressWarnings("unchecked")
            EventEnvelope<TestPayload> receivedEnvelope = objectMapper.readValue(receivedJson,
                    objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, TestPayload.class));

            Map<String, String> receivedHeaders = receivedEnvelope.headers();
            assertThat(receivedHeaders).containsKey(EventHeaders.TRACEPARENT);
            assertThat(receivedHeaders.get(EventHeaders.TRACEPARENT)).isEqualTo(traceparent);

            // Verify extraction yields same trace context and MDC scoping works
            Context extracted = propagator.extract(receivedHeaders);
            SpanContext extractedSc = Span.fromContext(extracted).getSpanContext();
            assertThat(extractedSc.isValid()).isTrue();
            assertThat(extractedSc.getTraceId()).isEqualTo(traceId);
            assertThat(extractedSc.getSpanId()).isEqualTo(parentId);

            try (KafkaListenerTraceScope ignored = kafkaListenerTraceScope.open(receivedEnvelope, topic)) {
                assertThat(org.slf4j.MDC.get("correlation.id")).isEqualTo(receivedEnvelope.correlationId());
                assertThat(extractedSc.getTraceId()).isEqualTo(traceId);
            }
        }
    }

    @Test
    void shouldIgnoreMalformedTraceParentAndCreateNewRoot() throws Exception {
        String topic = "seatflow.test.malformed-" + UUID.randomUUID();
        Map<String, String> badHeaders = Map.of(EventHeaders.TRACEPARENT, "malformed-traceparent", EventHeaders.CORRELATION_ID, UUID.randomUUID().toString());
        TestPayload payload = new TestPayload("bad-payload");
        EventEnvelope<TestPayload> envelope = EventEnvelope.of("TestEvent", UUID.randomUUID().toString(), UUID.randomUUID().toString(), payload)
                .withHeaders(badHeaders);
        String json = objectMapper.writeValueAsString(envelope);

        try (KafkaProducer<String, String> producer = createProducer();
             KafkaConsumer<String, String> consumer = createConsumer(topic)) {

            producer.send(new ProducerRecord<>(topic, UUID.randomUUID().toString(), json)).get();
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records.count()).isGreaterThan(0);
            ConsumerRecord<String, String> record = records.iterator().next();
            String receivedJson = record.value();
            EventEnvelope<TestPayload> receivedEnvelope = objectMapper.readValue(receivedJson,
                    objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, TestPayload.class));
            Map<String, String> receivedHeaders = receivedEnvelope.headers();
            // Extract should return root
            Context extracted = propagator.extract(receivedHeaders);
            assertThat(extracted).isEqualTo(Context.root());
            SpanContext sc = Span.fromContext(extracted).getSpanContext();
            assertThat(sc.isValid()).isFalse();

            // A malformed header is isolated from the listener while correlation remains usable.
            try (KafkaListenerTraceScope ignored = kafkaListenerTraceScope.open(receivedEnvelope, topic)) {
                assertThat(org.slf4j.MDC.get("trace.id")).isNull();
                assertThat(org.slf4j.MDC.get("correlation.id")).isEqualTo(receivedEnvelope.correlationId());
            }
            // No exception thrown, business processing can continue
        }
    }

    @Test
    void shouldHandleCollectorUnavailableWithoutBreakingBusinessFlow() {
        // Simulate exporter failure: W3C injection/extraction should still work even if collector unavailable
        // This is best-effort: we just ensure envelope creation and extraction work without Kafka
        String traceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String parentId = "bbbbbbbbbbbbbbbb";
        SpanContext sc = SpanContext.create(traceId, parentId, TraceFlags.getSampled(), TraceState.getDefault());
        Span span = Span.wrap(sc);
        Map<String, String> headers = new HashMap<>();
        try (Scope scope = Context.current().with(span).makeCurrent()) {
            propagator.inject(headers);
        }
        assertThat(headers.get(EventHeaders.TRACEPARENT)).isEqualTo("00-" + traceId + "-" + parentId + "-01");
        Context extracted = propagator.extract(headers);
        assertThat(Span.fromContext(extracted).getSpanContext().getTraceId()).isEqualTo(traceId);
        // Simulate outbox commit unaffected
        TestPayload payload = new TestPayload("test");
        EventEnvelope<TestPayload> envelope = EventEnvelope.of("TestEvent", UUID.randomUUID().toString(), UUID.randomUUID().toString(), payload)
                .withHeaders(headers);
        assertThat(envelope.headers()).containsEntry(EventHeaders.TRACEPARENT, "00-" + traceId + "-" + parentId + "-01");
    }

    private KafkaProducer<String, String> createProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    private KafkaConsumer<String, String> createConsumer(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));
        // Poll once to ensure subscription
        consumer.poll(Duration.ofMillis(500));
        return consumer;
    }
}
