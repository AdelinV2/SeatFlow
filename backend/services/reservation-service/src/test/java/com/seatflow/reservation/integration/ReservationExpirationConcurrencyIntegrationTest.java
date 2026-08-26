package com.seatflow.reservation.integration;

import com.seatflow.reservation.client.EventClient;
import com.seatflow.reservation.client.dto.EventPricingDetails;
import com.seatflow.reservation.model.entity.Reservation;
import com.seatflow.reservation.model.entity.SeatHold;
import com.seatflow.reservation.model.enums.ReservationStatus;
import com.seatflow.reservation.model.enums.SeatHoldStatus;
import com.seatflow.reservation.repository.OutboxEventRepository;
import com.seatflow.reservation.repository.ReservationRepository;
import com.seatflow.reservation.repository.SeatHoldRepository;
import com.seatflow.reservation.service.ReservationService;
import com.seatflow.reservation.web.dto.request.CreateReservationRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ReservationExpirationConcurrencyIntegrationTest {

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
    private SeatHoldRepository seatHoldRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void concurrentSweepExpiresExactlyOnceAndReleasesSeats() throws Exception {
        UUID eventId = UUID.randomUUID();
        List<UUID> seatIds = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID seatId = UUID.randomUUID();
            seatIds.add(seatId);
            Reservation reservation = Reservation.builder()
                    .eventId(eventId)
                    .customerEmail("guest-" + i + "@seatflow.com")
                    .status(ReservationStatus.PENDING)
                    .expiresAt(Instant.now().minus(1, ChronoUnit.MINUTES))
                    .idempotencyKey("expire-conc-" + i)
                    .totalAmount(new BigDecimal("10.00"))
                    .seatCount(1)
                    .userId(null)
                    .build();
            SeatHold hold = SeatHold.builder()
                    .eventId(eventId)
                    .seatId(seatId)
                    .status(SeatHoldStatus.HELD)
                    .price(new BigDecimal("10.00"))
                    .build();
            reservation.addSeatHold(hold);
            reservationRepository.saveAndFlush(reservation);
        }
        entityManager.clear();

        int threadCount = 5;
        ExecutorService exec = Executors.newFixedThreadPool(threadCount);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> reservationService.expireHoldReservations(Instant.now(), 10));
        }

        List<Future<Integer>> futures = exec.invokeAll(tasks);
        exec.shutdown();
        assertThat(exec.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        for (Future<Integer> f : futures) {
            f.get();
        }

        assertThat(reservationRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.EXPIRED)
                .count()).isEqualTo(50);

        List<SeatHold> holds = seatHoldRepository.findAll();
        assertThat(holds).hasSize(50);
        assertThat(holds).allMatch(h -> h.getStatus() == SeatHoldStatus.RELEASED);

        assertThat(outboxEventRepository.findAll().stream()
                .filter(o -> "ReservationExpiredEvent".equals(o.getEventType()))
                .count()).isEqualTo(50);

        UUID releasedSeat = seatIds.get(0);
        when(eventClient.getEventSeatPricing(any(), any())).thenReturn(new EventPricingDetails(
                eventId, "PUBLISHED", Instant.now().plusSeconds(3600), List.of(releasedSeat),
                Map.of(releasedSeat, new BigDecimal("10.00"))));

        var response = reservationService.createReservation(
                new CreateReservationRequest(eventId, "newguest@seatflow.com",
                        List.of(releasedSeat), List.of(new BigDecimal("10.00")), "idem-subsequent"),
                UUID.randomUUID());

        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(seatHoldRepository.findAll().stream()
                .filter(h -> h.getSeatId().equals(releasedSeat) && h.getStatus() == SeatHoldStatus.HELD)
                .count()).isEqualTo(1);
    }
}
