package com.seatflow.ticket.messaging.consumer;

import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.ticket.client.ReservationServiceClient;
import com.seatflow.ticket.client.dto.ReservationClientResponse;
import com.seatflow.ticket.messaging.event.PaymentCompletedEvent;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.repository.OutboxEventRepository;
import com.seatflow.ticket.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class PaymentCompletedEventListenerIntegrationTest {

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
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ReservationServiceClient reservationServiceClient;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void issuesTicketsAndOutboxEventsAndIsIdempotent() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String email = "buyer@example.com";

        UUID seatId1 = UUID.randomUUID();
        UUID seatId2 = UUID.randomUUID();
        ReservationClientResponse reservation = new ReservationClientResponse(
                reservationId,
                eventId,
                userId,
                email,
                "CONFIRMED",
                new BigDecimal("100.00"),
                2,
                List.of(
                        new ReservationClientResponse.HeldSeatClientDto(UUID.randomUUID(), seatId1, "HELD", new BigDecimal("50.00")),
                        new ReservationClientResponse.HeldSeatClientDto(UUID.randomUUID(), seatId2, "HELD", new BigDecimal("50.00"))
                ),
                Instant.now(),
                Instant.now()
        );
        when(reservationServiceClient.getReservationById(eq(reservationId))).thenReturn(Optional.of(reservation));

        PaymentCompletedEvent event = new PaymentCompletedEvent(
                paymentId,
                reservationId,
                userId,
                email,
                eventId,
                new BigDecimal("100.00"),
                new BigDecimal("19.00"),
                new BigDecimal("81.00"),
                "USD",
                "pi_test_123",
                Instant.now()
        );

        EventEnvelope<PaymentCompletedEvent> envelope =
                EventEnvelope.of("PaymentCompleted", paymentId.toString(), "corr-1", event);

        kafkaTemplate.send(EventTopics.PAYMENT_EVENTS, paymentId.toString(), envelope)
                .get(10, TimeUnit.SECONDS);

        await(Duration.ofSeconds(20), () -> ticketRepository.findByPaymentId(paymentId).size() == 2);

        List<Ticket> tickets = ticketRepository.findByPaymentId(paymentId);
        assertThat(tickets).hasSize(2);
        for (Ticket ticket : tickets) {
            assertThat(ticket.getPrice()).isEqualByComparingTo("50.00");
            assertThat(ticket.getTaxAmount()).isEqualByComparingTo("9.50");
            assertThat(ticket.getNetAmount()).isEqualByComparingTo("40.50");
        }

        assertThat(outboxEventRepository.count()).isEqualTo(2);

        // Idempotency: resending the same event must not create duplicate tickets
        EventEnvelope<PaymentCompletedEvent> duplicate =
                EventEnvelope.of("PaymentCompleted", paymentId.toString(), "corr-2", event);
        kafkaTemplate.send(EventTopics.PAYMENT_EVENTS, paymentId.toString(), duplicate)
                .get(10, TimeUnit.SECONDS);

        await(Duration.ofSeconds(20), () -> true);
        assertThat(ticketRepository.findByPaymentId(paymentId)).hasSize(2);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
    }

    private void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Condition not satisfied within " + timeout);
    }
}
