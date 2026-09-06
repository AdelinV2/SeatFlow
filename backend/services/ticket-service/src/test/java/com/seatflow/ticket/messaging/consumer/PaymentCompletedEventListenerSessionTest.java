package com.seatflow.ticket.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.ticket.client.ReservationServiceClient;
import com.seatflow.ticket.client.dto.ReservationClientResponse;
import com.seatflow.ticket.messaging.event.PaymentCompletedEvent;
import com.seatflow.ticket.model.common.IssueTicketsCommand;
import com.seatflow.ticket.repository.TicketRepository;
import com.seatflow.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P12-004 session contract tests for the payment-completed consumer.
 *
 * <p>Proves: session-A reservations yield session-A tickets; a message missing the
 * required {@code eventSessionId} is rejected (never inferred from {@code eventId});
 * a session mismatch between payload and stored reservation is rejected (session B
 * never leaks onto a session-A ticket); duplicate deliveries stay idempotent.
 */
@ExtendWith(MockitoExtension.class)
class PaymentCompletedEventListenerSessionTest {

    private static final Instant STARTS_AT = Instant.parse("2026-10-05T19:00:00Z");
    private static final Instant ENDS_AT = Instant.parse("2026-10-05T21:00:00Z");

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private TicketService ticketService;

    @Mock
    private ReservationServiceClient reservationServiceClient;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private PaymentCompletedEventListener listener;

    @BeforeEach
    void setUp() {
        KafkaListenerTraceScope traceScope = mock(KafkaListenerTraceScope.class);
        when(traceScope.open(any(EventEnvelope.class), any(String.class))).thenReturn(traceScope);
        listener = new PaymentCompletedEventListener(
                ticketRepository, ticketService, reservationServiceClient, objectMapper, traceScope);
    }

    private PaymentCompletedEvent payload(UUID paymentId, UUID reservationId, UUID sessionId) {
        return new PaymentCompletedEvent(
                paymentId, reservationId, UUID.randomUUID(), "buyer@example.com",
                sessionId, UUID.randomUUID(), STARTS_AT, ENDS_AT, null,
                new BigDecimal("100.00"), new BigDecimal("19.00"), new BigDecimal("81.00"),
                "USD", "pi_test", Instant.now());
    }

    private ReservationClientResponse reservation(UUID reservationId, UUID sessionId, UUID seatId) {
        return new ReservationClientResponse(
                reservationId, sessionId, UUID.randomUUID(), UUID.randomUUID(), "buyer@example.com",
                "CONFIRMED", new BigDecimal("100.00"), 1,
                STARTS_AT, ENDS_AT, null,
                List.of(new ReservationClientResponse.HeldSeatClientDto(
                        UUID.randomUUID(), seatId, "HELD", new BigDecimal("100.00"))),
                Instant.now(), Instant.now());
    }

    private EventEnvelope<Object> envelope(PaymentCompletedEvent event, UUID paymentId) {
        EventEnvelope<PaymentCompletedEvent> typed =
                EventEnvelope.of("PaymentCompleted", paymentId.toString(), "corr-1", event);
        return new EventEnvelope<>(
                typed.eventId(), typed.eventType(), typed.occurredAt(), typed.correlationId(),
                typed.causationId(), typed.aggregateId(), typed.version(), typed.payload(), typed.headers());
    }

    @Test
    void sessionAReservationYieldsSessionATickets() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        PaymentCompletedEvent event = payload(paymentId, reservationId, sessionA);

        when(ticketRepository.existsByPaymentId(paymentId)).thenReturn(false);
        when(reservationServiceClient.getReservationById(eq(reservationId), any()))
                .thenReturn(Optional.of(reservation(reservationId, sessionA, seatId)));

        listener.onPaymentCompleted(envelope(event, paymentId));

        ArgumentCaptor<IssueTicketsCommand> captor = ArgumentCaptor.forClass(IssueTicketsCommand.class);
        verify(ticketService).issueTickets(captor.capture());
        IssueTicketsCommand command = captor.getValue();
        assertThat(command.eventSessionId()).isEqualTo(sessionA);
        assertThat(command.sessionStartsAt()).isEqualTo(STARTS_AT);
        assertThat(command.sessionEndsAt()).isEqualTo(ENDS_AT);
        assertThat(command.seats()).hasSize(1);
        assertThat(command.seats().getFirst().seatId()).isEqualTo(seatId);
    }

    @Test
    void sessionMismatchIsRejectedAndIssuesNothing() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        PaymentCompletedEvent event = payload(paymentId, reservationId, sessionA);

        when(ticketRepository.existsByPaymentId(paymentId)).thenReturn(false);
        when(reservationServiceClient.getReservationById(eq(reservationId), any()))
                .thenReturn(Optional.of(reservation(reservationId, sessionB, UUID.randomUUID())));

        assertThatThrownBy(() -> listener.onPaymentCompleted(envelope(event, paymentId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        verify(ticketService, never()).issueTickets(any());
    }

    @Test
    void missingEventSessionIdIsRejectedNeverInferredFromEventId() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                paymentId, reservationId, UUID.randomUUID(), "buyer@example.com",
                null, UUID.randomUUID(), STARTS_AT, ENDS_AT, null,
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                "USD", "pi_test", Instant.now());

        when(ticketRepository.existsByPaymentId(paymentId)).thenReturn(false);

        assertThatThrownBy(() -> listener.onPaymentCompleted(envelope(event, paymentId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eventSessionId");
        // The stored reservation (which does carry session A) must not even be
        // consulted to backfill the missing identity, and nothing is issued.
        verify(reservationServiceClient, never()).getReservationById(any(), any());
        verify(ticketService, never()).issueTickets(any());
    }

    @Test
    void reservationWithoutSessionIsRejected() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        PaymentCompletedEvent event = payload(paymentId, reservationId, sessionA);

        when(ticketRepository.existsByPaymentId(paymentId)).thenReturn(false);
        when(reservationServiceClient.getReservationById(eq(reservationId), any()))
                .thenReturn(Optional.of(reservation(reservationId, null, UUID.randomUUID())));

        assertThatThrownBy(() -> listener.onPaymentCompleted(envelope(event, paymentId)))
                .isInstanceOf(IllegalStateException.class);
        verify(ticketService, never()).issueTickets(any());
    }

    @Test
    void duplicatePaymentCompletedIssuesNothing() {
        UUID paymentId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        PaymentCompletedEvent event = payload(paymentId, reservationId, UUID.randomUUID());

        when(ticketRepository.existsByPaymentId(paymentId)).thenReturn(true);

        listener.onPaymentCompleted(envelope(event, paymentId));

        verify(reservationServiceClient, never()).getReservationById(any(), any());
        verify(ticketService, never()).issueTickets(any());
    }
}
