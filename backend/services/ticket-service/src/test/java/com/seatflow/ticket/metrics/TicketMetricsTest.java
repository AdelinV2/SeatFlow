package com.seatflow.ticket.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.observability.metrics.SeatFlowMetricNames;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import com.seatflow.ticket.client.EventServiceClient;
import com.seatflow.ticket.client.SeatMapServiceClient;
import com.seatflow.ticket.mapper.TicketMapper;
import com.seatflow.ticket.messaging.producer.TicketOutboxPublisher;
import com.seatflow.ticket.model.common.IssueTicketsCommand;
import com.seatflow.ticket.model.entity.OutboxEvent;
import com.seatflow.ticket.model.entity.Ticket;
import com.seatflow.ticket.repository.OutboxEventRepository;
import com.seatflow.ticket.repository.TicketRepository;
import com.seatflow.ticket.repository.TicketValidationRepository;
import com.seatflow.ticket.service.PdfTicketGeneratorService;
import com.seatflow.ticket.service.QrCodeGeneratorService;
import com.seatflow.ticket.service.impl.TicketServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TicketMetricsTest {

    @Mock private TicketRepository ticketRepository;
    @Mock private TicketValidationRepository validationRepository;
    @Mock private OutboxEventRepository outboxRepository;
    @Mock private TicketMapper ticketMapper;
    @Mock private QrCodeGeneratorService qrCodeGeneratorService;
    @Mock private PdfTicketGeneratorService pdfTicketGeneratorService;
    @Mock private EventServiceClient eventServiceClient;
    @Mock private SeatMapServiceClient seatMapServiceClient;
    @Mock private W3cTraceContextPropagator propagator;

    private MeterRegistry registry;
    private TicketServiceImpl service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new TicketServiceImpl(ticketRepository, validationRepository, outboxRepository, ticketMapper,
                qrCodeGeneratorService, pdfTicketGeneratorService, eventServiceClient, seatMapServiceClient,
                objectMapper, propagator, registry);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(a -> a.getArgument(0));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(a -> a.getArgument(0));
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void shouldIncrementIssuedCounterOnCommittedTicketPersistence() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        IssueTicketsCommand.SeatTicketItem seat = new IssueTicketsCommand.SeatTicketItem(seatId, new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, "Standard");
        IssueTicketsCommand cmd = new IssueTicketsCommand(paymentId, reservationId, UUID.randomUUID(), "guest@example.com", "Guest", eventId, List.of(seat), "USD");

        var result = service.issueTickets(cmd);

        assertThat(registry.find("seatflow.tickets.issued.total").tags("source", "PAYMENT_COMPLETED").counter()).isNotNull();
        assertThat(registry.find("seatflow.tickets.issued.total").tags("source", "PAYMENT_COMPLETED").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("seatflow.tickets.issued.total").tags("ticketId", "x").counter()).isNull();
        assertThat(registry.find("seatflow.tickets.issued.total").tags("seatId", "x").counter()).isNull();
        assertThat(registry.find("seatflow.tickets.issued.total").tags("eventId", "x").counter()).isNull();
        var c = registry.find("seatflow.tickets.issued.total").tags("source", "PAYMENT_COMPLETED").counter();
        assertThat(c.getId().getTag("ticketId")).isNull();
    }

    @Test
    void shouldIncrementIssuedCounterForMultipleSeats() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        var seats = List.of(
                new IssueTicketsCommand.SeatTicketItem(UUID.randomUUID(), new BigDecimal("30.00"), BigDecimal.ZERO, BigDecimal.ZERO, "Standard"),
                new IssueTicketsCommand.SeatTicketItem(UUID.randomUUID(), new BigDecimal("40.00"), BigDecimal.ZERO, BigDecimal.ZERO, "VIP")
        );
        IssueTicketsCommand cmd = new IssueTicketsCommand(paymentId, reservationId, null, "guest@example.com", "Guest", eventId, seats, "USD");

        service.issueTickets(cmd);

        assertThat(registry.find("seatflow.tickets.issued.total").tags("source", "PAYMENT_COMPLETED").counter().count()).isEqualTo(2.0);
    }

    @Test
    void shouldRecordIssuedCounterOnlyAfterTransactionCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        IssueTicketsCommand cmd = commandWithOneSeat();

        service.issueTickets(cmd);

        assertThat(registry.find("seatflow.tickets.issued.total").counter()).isNull();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(registry.find("seatflow.tickets.issued.total")
                .tags("source", "PAYMENT_COMPLETED").counter().count()).isEqualTo(1.0);
    }

    @Test
    void shouldNotRecordIssuedCounterWhenOutboxPersistenceFails() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(outboxRepository.save(any(OutboxEvent.class)))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThatThrownBy(() -> service.issueTickets(commandWithOneSeat()))
                .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(registry.find("seatflow.tickets.issued.total").counter()).isNull();
    }

    @Test
    void shouldNotContainHighCardinalityTagsOnIssued() {
        UUID eventId = UUID.randomUUID();
        IssueTicketsCommand.SeatTicketItem seat = new IssueTicketsCommand.SeatTicketItem(UUID.randomUUID(), new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, "Standard");
        IssueTicketsCommand cmd = new IssueTicketsCommand(UUID.randomUUID(), UUID.randomUUID(), null, "guest@example.com", "Guest", eventId, List.of(seat), "USD");

        service.issueTickets(cmd);

        var counter = registry.find("seatflow.tickets.issued.total").tags("source", "PAYMENT_COMPLETED").counter();
        assertThat(counter).isNotNull();
        // Ensure no forbidden tags — verify high-cardinality tags are absent
        assertThat(counter.getId().getTag("ticketId")).isNull();
        assertThat(counter.getId().getTag("paymentId")).isNull();
        assertThat(counter.getId().getTag("reservationId")).isNull();
        assertThat(counter.getId().getTag("userId")).isNull();
        assertThat(counter.getId().getTag("seatId")).isNull();
        assertThat(counter.getId().getTag("eventId")).isNull();
    }

    @Test
    void shouldRecordOutboxLatencyAndRetry() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        var publisher = new TicketOutboxPublisher(outboxRepository, kafkaTemplate, objectMapper, registry, propagator);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("TicketIssued")
                .payload("{}")
                .createdAt(Instant.now().minusSeconds(3))
                .build();
        when(outboxRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(String.class), any(String.class), any(Object.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.markPublished(any(UUID.class), any(Instant.class))).thenReturn(1);

        publisher.publishPendingEvents();

        assertThat(registry.find("seatflow.outbox.publish.latency").timer()).isNotNull();
        assertThat(registry.find("seatflow.outbox.publish.latency").tags("service", "ticket-service", "event_type", "TicketIssued", "outcome", "SUCCESS").timer()).isNotNull();
    }

    @Test
    void shouldNotRecordSuccessfulOutboxLatencyWhenPublishedStateIsNotPersisted() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        var publisher = new TicketOutboxPublisher(outboxRepository, kafkaTemplate, objectMapper, registry, propagator);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("TicketIssued")
                .payload("{}")
                .retryCount(0)
                .createdAt(Instant.now().minusSeconds(1))
                .build();
        when(outboxRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(String.class), any(String.class), any(Object.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(outboxRepository.markPublished(any(UUID.class), any(Instant.class))).thenReturn(0);

        publisher.publishPendingEvents();

        assertThat(registry.find("seatflow.outbox.publish.latency")
                .tags("service", "ticket-service", "event_type", "TicketIssued", "outcome", "SUCCESS")
                .timer()).isNull();
    }

    @Test
    void shouldNotRecordSuccessfulOutboxLatencyWhenKafkaSendFails() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        var publisher = new TicketOutboxPublisher(outboxRepository, kafkaTemplate, objectMapper, registry, propagator);
        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateId(UUID.randomUUID())
                .eventType("TicketIssued")
                .payload("{}")
                .retryCount(0)
                .createdAt(Instant.now().minusSeconds(1))
                .build();
        when(outboxRepository.findUnpublishedForUpdate(5, 50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(String.class), any(String.class), any(Object.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        when(outboxRepository.incrementRetryCount(event.getId(), 5)).thenReturn(1);

        publisher.publishPendingEvents();

        assertThat(registry.find("seatflow.outbox.publish.latency")
                .tags("service", "ticket-service", "event_type", "TicketIssued", "outcome", "SUCCESS")
                .timer()).isNull();
        assertThat(registry.find(SeatFlowMetricNames.OUTBOX_RETRY_COUNT)
                .tags("service", "ticket-service", "event_type", "TicketIssued")
                .counter()).isNotNull();
    }

    @Test
    void shouldNotRollbackOnMeterFailure() {
        MeterRegistry failingRegistry = new SimpleMeterRegistry() {
            @Override
            public io.micrometer.core.instrument.Counter counter(String name, String... tags) {
                throw new RuntimeException("meter failure");
            }
        };
        TicketServiceImpl failingService = new TicketServiceImpl(ticketRepository, validationRepository, outboxRepository, ticketMapper,
                qrCodeGeneratorService, pdfTicketGeneratorService, eventServiceClient, seatMapServiceClient,
                objectMapper, propagator, failingRegistry);
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(a -> a.getArgument(0));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(a -> a.getArgument(0));
        UUID eventId = UUID.randomUUID();
        IssueTicketsCommand.SeatTicketItem seat = new IssueTicketsCommand.SeatTicketItem(UUID.randomUUID(), new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, "Standard");
        IssueTicketsCommand cmd = new IssueTicketsCommand(UUID.randomUUID(), UUID.randomUUID(), null, "guest@example.com", "Guest", eventId, List.of(seat), "USD");

        var result = failingService.issueTickets(cmd);
        assertThat(result).isNotNull();
    }

    private IssueTicketsCommand commandWithOneSeat() {
        return new IssueTicketsCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "guest@example.com",
                "Guest",
                UUID.randomUUID(),
                List.of(new IssueTicketsCommand.SeatTicketItem(
                        UUID.randomUUID(), new BigDecimal("50.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO, "Standard")),
                "USD");
    }
}
