package com.seatflow.ticket.messaging.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.ticket.messaging.event.TicketIssuedEvent;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.repository.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class TicketOutboxPublisherIntegrationTest {

    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(KAFKA_IMAGE);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
    }

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private TicketOutboxPublisher ticketOutboxPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void publishesTicketIssuedEventToKafkaAndMarksOutboxPublished() throws Exception {
        UUID ticketId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("120.00");
        BigDecimal taxAmount = new BigDecimal("20.00");
        BigDecimal netAmount = new BigDecimal("100.00");
        String ticketCode = "SF-TKT-ABC123";
        String qrCodeData = "https://seatflow.app/tickets/guest/SF-TKT-ABC123";

        TicketIssuedEvent payload = new TicketIssuedEvent(
                ticketId,
                reservationId,
                userId,
                "attendee@example.com",
                "Alice Attendee",
                eventId,
                seatId,
                price,
                taxAmount,
                netAmount,
                ticketCode,
                qrCodeData,
                Instant.now()
        );

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateId(ticketId)
                .eventType("TicketIssued")
                .payload(objectMapper.writeValueAsString(payload))
                .build();
        OutboxEvent saved = outboxRepository.save(outboxEvent);
        assertThat(saved.getPublishedAt()).isNull();

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                kafka.getBootstrapServers(), "test-ticket-outbox-group", false);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(EventTopics.TICKET_EVENTS));

            ticketOutboxPublisher.publishPendingEvents();

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, EventTopics.TICKET_EVENTS, Duration.ofSeconds(10));

            assertThat(record.key()).isEqualTo(ticketId.toString());

            EventEnvelope<?> envelope = objectMapper.readValue(record.value(), EventEnvelope.class);
            assertThat(envelope.eventType()).isEqualTo("TicketIssued");
            assertThat(envelope.aggregateId()).isEqualTo(ticketId.toString());

            @SuppressWarnings("unchecked")
            Map<String, Object> eventPayload = (Map<String, Object>) envelope.payload();
            assertThat(eventPayload.get("ticketCode")).isEqualTo(ticketCode);
            assertThat(new BigDecimal(eventPayload.get("price").toString())).isEqualByComparingTo(price);
            assertThat(new BigDecimal(eventPayload.get("taxAmount").toString())).isEqualByComparingTo(taxAmount);
            assertThat(new BigDecimal(eventPayload.get("netAmount").toString())).isEqualByComparingTo(netAmount);
        }

        OutboxEvent afterPublish = outboxRepository.findById(saved.getId()).orElseThrow();
        assertThat(afterPublish.getPublishedAt()).isNotNull();
        assertThat(afterPublish.getRetryCount()).isZero();
    }
}
