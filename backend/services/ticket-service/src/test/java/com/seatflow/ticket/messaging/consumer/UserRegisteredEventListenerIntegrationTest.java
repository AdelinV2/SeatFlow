package com.seatflow.ticket.messaging.consumer;

import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.events.EventTopics;
import com.seatflow.ticket.messaging.event.UserRegisteredEvent;
import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.model.enums.TicketStatus;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class UserRegisteredEventListenerIntegrationTest {

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

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void linksHistoricalGuestTicketsToNewlyRegisteredUser() throws Exception {
        UUID newUserId = UUID.randomUUID();
        String email = "guest.shopper@seatflow.com";

        for (int i = 0; i < 3; i++) {
            ticketRepository.save(Ticket.builder()
                    .reservationId(UUID.randomUUID())
                    .paymentId(UUID.randomUUID())
                    .customerEmail(email)
                    .eventId(UUID.randomUUID())
                    .seatId(UUID.randomUUID())
                    .price(new BigDecimal("50.00"))
                    .taxAmount(new BigDecimal("9.50"))
                    .netAmount(new BigDecimal("40.50"))
                    .ticketCode("SF-TKT-SEED-" + i + "-" + UUID.randomUUID())
                    .qrCodeData("https://seatflow.app/tickets/guest/SF-TKT-SEED-" + i)
                    .status(TicketStatus.VALID)
                    .build());
        }

        UserRegisteredEvent userEvent = new UserRegisteredEvent(newUserId, email, Instant.now());
        EventEnvelope<UserRegisteredEvent> envelope =
                EventEnvelope.of("UserRegistered", newUserId.toString(), "corr-1", userEvent);

        kafkaTemplate.send(EventTopics.USER_EVENTS, newUserId.toString(), envelope)
                .get(10, TimeUnit.SECONDS);

        await(Duration.ofSeconds(20), () -> ticketRepository.findAll().stream()
                .filter(t -> email.equals(t.getCustomerEmail()))
                .allMatch(t -> newUserId.equals(t.getUserId())));

        List<Ticket> claimed = ticketRepository.findAll().stream()
                .filter(t -> email.equals(t.getCustomerEmail()))
                .toList();
        assertThat(claimed).hasSize(3);
        assertThat(claimed).allMatch(t -> newUserId.equals(t.getUserId()));
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
