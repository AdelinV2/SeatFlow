package com.seatflow.reservation.integration;

import com.seatflow.common.events.EventTopics;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
import com.seatflow.reservation.messaging.producer.OutboxEventPublisher;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.ClassUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * REV-003 broker-backed outage isolation evidence (TASK-P14-007 §7.17 steps 1-4).
 *
 * <p>Unlike {@code AnalyticsAbsenceIsolationTest} (mocked {@code KafkaTemplate}), this class
 * uses a real embedded broker with the production {@code KafkaTemplate} + transactional-outbox
 * publisher wiring: analytics is genuinely absent (never started, not on the classpath), the
 * checkout flow completes, and the {@code ReservationHeldEvent} is retained in the broker for
 * a later analytics consumer. The analytics-side catch-up half (step 5) is proven by
 * {@code AnalyticsOutageCatchUpIntegrationTest} in analytics-service; together with
 * {@code infra/scripts/verify-analytics-isolation.sh} they form the explicit integration
 * harness preserving service boundaries instead of mocking Kafka away.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(topics = {EventTopics.RESERVATION_EVENTS}, partitions = 1)
class AnalyticsAbsenceBrokerIsolationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_broker_isolation_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
        registry.add("reservation.cleanup.enabled", () -> "false");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private EventClient eventClient;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Test
    @DisplayName("analytics stays absent from the reservation runtime")
    void analyticsAbsentFromRuntime() {
        assertThat(context.getBeansOfType(Object.class).keySet().stream()
                .filter(name -> name.toLowerCase().contains("analytic"))
                .toList()).isEmpty();
        assertThat(ClassUtils.isPresent(
                "com.seatflow.analytics.AnalyticsServiceApplication",
                getClass().getClassLoader())).isFalse();
    }

    @Test
    @DisplayName("checkout completes over a real broker with analytics absent and retains the event")
    void checkoutRetainsEventInBrokerWithoutAnalytics() {
        UUID sessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID()));
        when(eventClient.getEventSeatPricing(any(), any())).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", List.of(seatId), Map.of(seatId, new BigDecimal("10.00"))));

        var response = reservationService.createReservation(
                new CreateReservationRequest(sessionId, "guest@example.com",
                        List.of(seatId), List.of(new BigDecimal("10.00")),
                        "idem-broker-isolation-" + UUID.randomUUID()),
                UUID.randomUUID());

        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.eventSessionId()).isEqualTo(sessionId);

        // Production outbox wiring publishes through the REAL KafkaTemplate: no mock stands
        // in for the broker on the flow under test.
        outboxEventPublisher.publishPendingEvents();

        assertThat(outboxEventRepository.findAll())
                .allMatch(outbox -> outbox.getPublishedAt() != null);

        // The held event is retained in the broker (observable by any later consumer group,
        // e.g. analytics starting after its outage) — proof it does not depend on analytics.
        String retained = awaitBrokerRecordContaining(
                EventTopics.RESERVATION_EVENTS, response.id().toString(), Duration.ofSeconds(30));
        assertThat(retained).contains("ReservationHeldEvent");
        assertThat(retained).contains(sessionId.toString());
        assertThat(kafkaTemplate).isNotNull();
        assertThat(embeddedKafka).isNotNull();
    }

    private String awaitBrokerRecordContaining(String topic, String fragment, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "isolation-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer =
                     new DefaultKafkaConsumerFactory<String, String>(props).createConsumer()) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value() != null && record.value().contains(fragment)) {
                        return record.value();
                    }
                }
            }
        }
        throw new IllegalStateException(
                "Timed out waiting for broker record containing " + fragment + " on " + topic);
    }
}
