package com.seatflow.reservation.integration;

import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.client.dto.SessionBookingContextDto;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P12-008 outbox failure path (aggregate leg): a failure at the outbox-write
 * boundary inside the creation transaction must roll back the whole aggregate
 * — no reservation row, no hold row, no orphan side effect.
 *
 * <p>Uses a real PostgreSQL (Testcontainers) with only the outbox repository
 * replaced by a mock that throws on {@code save}, so the production
 * transaction boundary is exercised end to end. The publisher leg (broker
 * failure leaves the committed outbox unpublished/retryable with the
 * aggregate untouched) lives in
 * {@code SessionScopedInventoryIntegrationTest} order 15 against the real
 * repositories.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ReservationOutboxFailurePathIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_reservation_outbox_fail_test")
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

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private EventClient eventClient;

    @MockitoBean
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SeatHoldRepository seatHoldRepository;

    @BeforeEach
    void stubPublisherPoll() {
        // Keep the background outbox publisher idle: it only polls.
        lenient().when(outboxEventRepository.findUnpublishedForUpdate(anyInt(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void outboxWriteFailureRollsBackReservationAndHolds() {
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID()));
        when(eventClient.getEventSeatPricing(eq(eventId), any())).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", List.of(seat), Map.of(seat, new BigDecimal("22.00"))));

        // Force the failure exactly at the outbox-write boundary: the
        // aggregate insert succeeds first, then the outbox save throws inside
        // the same transaction.
        when(outboxEventRepository.save(any()))
                .thenThrow(new RuntimeException("forced outbox-write failure"));

        String idempotencyKey = "p12-008-outbox-fail-" + UUID.randomUUID();
        long reservationsBefore = reservationRepository.count();
        long holdsBefore = seatHoldRepository.count();

        var request = new CreateReservationRequest(
                sessionId, "guest@seatflow.com", List.of(seat), List.of(new BigDecimal("22.00")),
                idempotencyKey);
        assertThatThrownBy(() -> reservationService.createReservation(request, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("forced outbox-write failure");

        verify(outboxEventRepository).save(any());

        // Complete rollback: no reservation, no hold, nothing left behind.
        assertThat(reservationRepository.findWithSeatHoldsByIdempotencyKey(idempotencyKey)).isEmpty();
        assertThat(reservationRepository.count()).isEqualTo(reservationsBefore);
        assertThat(seatHoldRepository.count()).isEqualTo(holdsBefore);
    }

    @Test
    void successfulCreateStillWritesOutboxAfterFailure() {
        // Sanity: with a working outbox boundary the same flow commits both
        // rows (guards against the mock setup leaking across tests — each
        // test gets fresh stubbing, but the aggregate path is re-proven).
        UUID eventId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        when(eventClient.getSessionBookingContext(sessionId)).thenReturn(new SessionBookingContextDto(
                sessionId, eventId, "PUBLISHED", "SCHEDULED",
                Instant.now().plusSeconds(86400), Instant.now().plusSeconds(90000),
                null, null, UUID.randomUUID()));
        when(eventClient.getEventSeatPricing(eq(eventId), any())).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", new ArrayList<>(List.of(seat)), Map.of(seat, new BigDecimal("22.00"))));

        String idempotencyKey = "p12-008-outbox-ok-" + UUID.randomUUID();
        var response = reservationService.createReservation(
                new CreateReservationRequest(sessionId, "guest@seatflow.com",
                        List.of(seat), List.of(new BigDecimal("22.00")), idempotencyKey),
                null);

        assertThat(reservationRepository.findWithSeatHoldsByIdempotencyKey(idempotencyKey)).isPresent();
        verify(outboxEventRepository).save(any());
        assertThat(response.eventSessionId()).isEqualTo(sessionId);
    }
}
