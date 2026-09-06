package com.seatflow.ticket.messaging.producer;

import com.seatflow.ticket.model.common.IssueTicketsCommand;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.repository.OutboxEventRepository;
import com.seatflow.ticket.repository.TicketRepository;
import com.seatflow.ticket.service.TicketService;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P12-008 outbox failure path (ticket publisher leg, REV-005): tickets and
 * their {@code TicketIssued} outbox rows commit together in the issuance
 * transaction, and a broker failure must leave the committed outbox rows
 * unpublished/retryable without touching the authoritative tickets.
 *
 * <p>Uses a real PostgreSQL (Testcontainers) with real repositories, the real
 * {@link TicketService} issuance transaction and the real
 * {@link TicketOutboxPublisher}; only the Kafka broker call is forced to
 * fail. This mirrors the reservation publisher-leg proof
 * ({@code SessionScopedInventoryIntegrationTest} order 15) on the ticket leg
 * of the chain.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TicketOutboxBrokerFailureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("outbox.publisher.fixed-delay-ms", () -> "60000");
    }

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketOutboxPublisher ticketOutboxPublisher;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void brokerPublishFailureLeavesCommittedOutboxRetryableAndTicketsIntact() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        // P12-008 chain vector: one session-A identity + snapshot instants;
        // session B exists only as a leakage oracle.
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        Instant startsA = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsA = Instant.parse("2026-10-05T21:00:00Z");
        UUID seatId = UUID.randomUUID();

        IssueTicketsCommand command = new IssueTicketsCommand(
                paymentId,
                reservationId,
                userId,
                "buyer@example.com",
                "Jane Doe",
                sessionA,
                eventId,
                startsA,
                endsA,
                null,
                List.of(new IssueTicketsCommand.SeatTicketItem(
                        seatId, new BigDecimal("50.00"), new BigDecimal("9.50"), new BigDecimal("40.50"))),
                "USD");

        ticketService.issueTickets(command);

        List<Ticket> tickets = ticketRepository.findByPaymentId(paymentId);
        assertThat(tickets).hasSize(1);
        assertThat(tickets.getFirst().getEventSessionId()).isEqualTo(sessionA);
        assertThat(tickets.getFirst().getSessionStartsAt()).isEqualTo(startsA);
        assertThat(tickets.getFirst().getSessionEndsAt()).isEqualTo(endsA);

        List<OutboxEvent> pending = outboxEventRepository.findAll().stream()
                .filter(o -> "TicketIssued".equals(o.getEventType())
                        && tickets.stream().anyMatch(t -> t.getId().equals(o.getAggregateId())))
                .toList();
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().getPublishedAt()).isNull();
        assertThat(pending.getFirst().getPayload()).contains(sessionA.toString());
        assertThat(pending.getFirst().getPayload()).doesNotContain(sessionB.toString());
        int retryBefore = pending.getFirst().getRetryCount();

        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("forced broker failure"));

        ticketOutboxPublisher.publishPendingEvents();

        List<OutboxEvent> after = outboxEventRepository.findAll().stream()
                .filter(o -> "TicketIssued".equals(o.getEventType())
                        && tickets.stream().anyMatch(t -> t.getId().equals(o.getAggregateId())))
                .toList();
        assertThat(after).hasSize(1);
        assertThat(after.getFirst().getPublishedAt()).isNull();
        assertThat(after.getFirst().getRetryCount()).isGreaterThan(retryBefore);

        // The authoritative tickets are untouched by the publisher failure.
        List<Ticket> stored = ticketRepository.findByPaymentId(paymentId);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getEventSessionId()).isEqualTo(sessionA);
        assertThat(stored.getFirst().getSessionStartsAt()).isEqualTo(startsA);
        assertThat(stored.getFirst().getSessionEndsAt()).isEqualTo(endsA);
    }
}
