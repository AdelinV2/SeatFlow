package com.seatflow.reservation.integration;

import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.messaging.producer.OutboxEventPublisher;
import com.seatflow.reservation.model.entity.OutboxEvent;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ReservationServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
        registry.add("reservation.cleanup.enabled", () -> "false");
    }

    // Prevent the OAuth2 resource server from performing a network call to the dummy issuer on startup.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private EventClient eventClient;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private OutboxEventPublisher outboxEventPublisher;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    @Test
    void createReservationWritesOutboxAndPublisherDeliversToKafka() {
        UUID eventId = UUID.randomUUID();
        UUID seatId1 = UUID.randomUUID();
        UUID seatId2 = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId1, seatId2);
        List<BigDecimal> prices = List.of(new BigDecimal("10.00"), new BigDecimal("20.00"));
        String idempotencyKey = "idem-integration-" + UUID.randomUUID();

        EventPricingDetails pricing = new EventPricingDetails(
                eventId, "PUBLISHED", Instant.now().plusSeconds(3600), seatIds,
                Map.of(seatId1, new BigDecimal("10.00"), seatId2, new BigDecimal("20.00")));
        when(eventClient.getEventSeatPricing(any(), any())).thenReturn(pricing);

        SendResult<String, String> sendResult = mock();
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(sendResult));

        CreateReservationRequest request = new CreateReservationRequest(
                eventId, "guest@example.com", seatIds, prices, idempotencyKey);

        var response = reservationService.createReservation(request, UUID.randomUUID());

        // 1. Reservation is PENDING and seat holds are persisted as HELD
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        List<SeatHold> holds = seatHoldRepository.findAll();
        assertThat(holds).hasSize(2);
        assertThat(holds).allMatch(h -> h.getStatus() == SeatHoldStatus.HELD);

        // 2. An unpublished ReservationHeldEvent outbox record exists
        List<OutboxEvent> before = outboxEventRepository.findAll();
        assertThat(before).hasSize(1);
        OutboxEvent pending = before.get(0);
        assertThat(pending.getEventType()).isEqualTo("ReservationHeldEvent");
        assertThat(pending.getAggregateId()).isEqualTo(response.id());
        assertThat(pending.getPublishedAt()).isNull();
        assertThat(pending.getRetryCount()).isZero();

        // 3. Manually trigger the outbox publisher
        outboxEventPublisher.publishPendingEvents();

        // 4. Kafka received the message keyed by reservationId on the correct topic
        var topicCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), anyString());
        assertThat(topicCaptor.getValue()).isEqualTo("seatflow.reservation.events");
        assertThat(keyCaptor.getValue()).isEqualTo(response.id().toString());

        // 5. Outbox record is now published (published_at set, retry_count == 0)
        OutboxEvent published = outboxEventRepository.findById(pending.getId()).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getRetryCount()).isZero();
    }
}
