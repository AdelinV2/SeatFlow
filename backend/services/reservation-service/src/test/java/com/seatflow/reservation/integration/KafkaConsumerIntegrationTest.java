package com.seatflow.reservation.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.messaging.event.PaymentCompletedEvent;
import com.seatflow.reservation.messaging.event.UserRegisteredEvent;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(topics = {EventTopics.PAYMENT_EVENTS, EventTopics.USER_EVENTS})
class KafkaConsumerIntegrationTest {

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
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
        registry.add("reservation.cleanup.enabled", () -> "false");
    }

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private EventClient eventClient;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void paymentCompletedConfirmsReservationAndMarksSeatsSold() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId1 = UUID.randomUUID();
        UUID seatId2 = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId1, seatId2);
        List<BigDecimal> prices = List.of(new BigDecimal("10.00"), new BigDecimal("20.00"));
        String idempotencyKey = "idem-pay-" + UUID.randomUUID();

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                Instant.now().plusSeconds(3600), seatIds,
                Map.of(seatId1, new BigDecimal("10.00"), seatId2, new BigDecimal("20.00")));
        when(eventClient.getEventSeatPricing(any(), any())).thenReturn(pricing);

        var response = reservationService.createReservation(
                new CreateReservationRequest(eventId, "guest@seatflow.com", seatIds, prices, idempotencyKey), null);
        UUID reservationId = response.id();
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);

        PaymentCompletedEvent evt = new PaymentCompletedEvent(
                UUID.randomUUID().toString(), reservationId.toString(), UUID.randomUUID().toString(),
                "guest@seatflow.com", eventId.toString(), new BigDecimal("30.00"), "USD",
                "pi_123", Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("PaymentCompleted", reservationId.toString(), UUID.randomUUID().toString(), evt));

        kafkaTemplate.send(EventTopics.PAYMENT_EVENTS, reservationId.toString(), json).get();

        Reservation confirmed = awaitStatus(reservationId, ReservationStatus.CONFIRMED, Duration.ofSeconds(15));
        assertThat(confirmed.getSeatHolds()).isNotEmpty();
        assertThat(confirmed.getSeatHolds()).allMatch(h -> h.getStatus() == SeatHoldStatus.SOLD);
    }

    @Test
    void userRegisteredLinksGuestReservationsToNewAccount() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        List<UUID> seatIds = List.of(seatId);
        List<BigDecimal> prices = List.of(new BigDecimal("15.00"));
        String idempotencyKey = "idem-user-" + UUID.randomUUID();
        String guestEmail = "guest-link-" + UUID.randomUUID() + "@seatflow.com";

        EventPricingDetails pricing = new EventPricingDetails(eventId, "PUBLISHED",
                Instant.now().plusSeconds(3600), seatIds,
                Map.of(seatId, new BigDecimal("15.00")));
        when(eventClient.getEventSeatPricing(any(), any())).thenReturn(pricing);

        var response = reservationService.createReservation(
                new CreateReservationRequest(eventId, guestEmail, seatIds, prices, idempotencyKey), null);
        UUID reservationId = response.id();
        assertThat(reservationId).isNotNull();

        UUID newUserId = UUID.randomUUID();
        UserRegisteredEvent evt = new UserRegisteredEvent(newUserId.toString(), guestEmail, "Guest User", Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("UserRegistered", newUserId.toString(), UUID.randomUUID().toString(), evt));

        kafkaTemplate.send(EventTopics.USER_EVENTS, newUserId.toString(), json).get();

        Reservation linked = awaitUserId(reservationId, newUserId, Duration.ofSeconds(15));
        assertThat(linked.getUserId()).isEqualTo(newUserId);
    }

    private Reservation awaitStatus(UUID id, ReservationStatus expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Reservation r = reservationRepository.findWithSeatHoldsById(id).orElse(null);
            if (r != null && r.getStatus() == expected) {
                return r;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Reservation " + id + " did not reach " + expected);
    }

    private Reservation awaitUserId(UUID id, UUID expectedUserId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Reservation r = reservationRepository.findById(id).orElse(null);
            if (r != null && expectedUserId.equals(r.getUserId())) {
                return r;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Reservation " + id + " was not linked to user " + expectedUserId);
    }
}
