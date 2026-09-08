package com.seatflow.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.analytics.metrics.AnalyticsConsumerMetrics;
import com.seatflow.analytics.repository.ProcessedEventRepository;
import com.seatflow.common.events.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProjectionEventProcessorIntegrationTest.TestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProjectionEventProcessorIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_analytics_proc_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        AnalyticsConsumerMetrics consumerMetrics(MeterRegistry meterRegistry) {
            return new AnalyticsConsumerMetrics(meterRegistry);
        }

        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }

        @Bean
        AnalyticsEventDispatcher dispatcher(CountingHandler countingHandler) {
            return new AnalyticsEventDispatcher(List.of(countingHandler));
        }

        @Bean
        ProjectionEventProcessor processor(ProcessedEventRepository repository,
                                           AnalyticsEventDispatcher dispatcher) {
            return new ProjectionEventProcessor(repository, dispatcher);
        }
    }

    static class CountingHandler implements ProjectionHandler {
        final AtomicInteger invocations = new AtomicInteger();
        volatile boolean failNext = false;
        volatile boolean alwaysFail = false;

        @Override
        public Set<String> eventTypes() {
            return Set.of("PaymentCompleted");
        }

        @Override
        public void project(EventEnvelope<JsonNode> envelope, ConsumerRecordMetadata metadata) {
            invocations.incrementAndGet();
            if (alwaysFail || failNext) {
                failNext = false;
                throw new IllegalStateException("simulated transient projection failure");
            }
        }
    }

    @Autowired
    private ProjectionEventProcessor processor;

    @Autowired
    private CountingHandler countingHandler;

    @Autowired
    private ProcessedEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void cleanState() {
        countingHandler.invocations.set(0);
        countingHandler.failNext = false;
        countingHandler.alwaysFail = false;
        repository.deleteAll();
    }

    @Test
    @DisplayName("Same eventId delivered twice runs the handler once")
    void duplicateDeliveryProjectsOnce() {
        EventEnvelope<JsonNode> envelope = paymentEnvelope("evt-dup-" + UUID.randomUUID());
        ConsumerRecordMetadata metadata = metadata(0L);

        assertThat(processor.process(envelope, metadata))
                .isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(processor.process(envelope, metadata))
                .isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(countingHandler.invocations.get()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrent duplicate delivery claims exactly once")
    void concurrentDuplicateClaimsOnce() throws Exception {
        EventEnvelope<JsonNode> envelope = paymentEnvelope("evt-conc-" + UUID.randomUUID());
        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ProjectionEventProcessor.Outcome>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                final long offset = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("contenders did not align");
                    }
                    return processor.process(envelope, metadata(offset));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int processed = 0;
            int duplicates = 0;
            for (Future<ProjectionEventProcessor.Outcome> future : futures) {
                switch (future.get(30, TimeUnit.SECONDS)) {
                    case PROCESSED -> processed++;
                    case DUPLICATE -> duplicates++;
                    case IGNORED -> throw new IllegalStateException("unexpected IGNORED");
                }
            }
            assertThat(processed).isEqualTo(1);
            assertThat(duplicates).isEqualTo(contenders - 1);
            assertThat(countingHandler.invocations.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Distinct eventIds for the same aggregate are distinct semantic events")
    void distinctEventIdsBothProcess() {
        String reservationId = UUID.randomUUID().toString();
        EventEnvelope<JsonNode> first = paymentEnvelope("evt-dist-" + UUID.randomUUID(), reservationId);
        EventEnvelope<JsonNode> second = paymentEnvelope("evt-dist-" + UUID.randomUUID(), reservationId);

        assertThat(processor.process(first, metadata(0L)))
                .isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(processor.process(second, metadata(1L)))
                .isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(countingHandler.invocations.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Handler failure rolls back the claim and a retry can succeed")
    void handlerFailureRollsBackClaim() {
        EventEnvelope<JsonNode> envelope = paymentEnvelope("evt-rollback-" + UUID.randomUUID());
        countingHandler.failNext = true;

        assertThatThrownBy(() -> processor.process(envelope, metadata(0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated transient");

        Integer markers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, envelope.eventId());
        assertThat(markers).isZero();

        assertThat(processor.process(envelope, metadata(0L)))
                .isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(countingHandler.invocations.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Redelivery after commit (offset window) performs no second mutation")
    void redeliveryAfterCommitIsNoOp() {
        EventEnvelope<JsonNode> envelope = paymentEnvelope("evt-redeliver-" + UUID.randomUUID());

        assertThat(processor.process(envelope, metadata(7L)))
                .isEqualTo(ProjectionEventProcessor.Outcome.PROCESSED);
        assertThat(processor.process(envelope, new ConsumerRecordMetadata(
                        "seatflow.payment.events", 0, 8L, "same-key")))
                .isEqualTo(ProjectionEventProcessor.Outcome.DUPLICATE);
        assertThat(countingHandler.invocations.get()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    private EventEnvelope<JsonNode> paymentEnvelope(String eventId) {
        return paymentEnvelope(eventId, UUID.randomUUID().toString());
    }

    private EventEnvelope<JsonNode> paymentEnvelope(String eventId, String reservationId) {
        JsonNode payload = objectMapper.createObjectNode()
                .put("paymentId", UUID.randomUUID().toString())
                .put("reservationId", reservationId);
        return new EventEnvelope<>(eventId, "PaymentCompleted", Instant.now(),
                "corr-1", null, UUID.randomUUID().toString(), 1, payload);
    }

    private static ConsumerRecordMetadata metadata(long offset) {
        return new ConsumerRecordMetadata("seatflow.payment.events", 0, offset, "key");
    }
}
