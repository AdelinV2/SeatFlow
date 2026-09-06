package com.seatflow.notification.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.notification.messaging.event.TicketIssuedEvent;
import com.seatflow.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketIssuedEventListenerTest {

    @Mock
    private NotificationService notificationService;

    private ObjectMapper objectMapper;
    private TicketIssuedEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        listener = new TicketIssuedEventListener(notificationService, objectMapper, mock(com.seatflow.common.observability.tracing.KafkaListenerTraceScope.class));
    }

    @Test
    @DisplayName("Should unwrap TicketIssued envelope and delegate to NotificationService")
    void shouldProcessTicketIssuedEvent() {
        UUID ticketId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant startsA = Instant.parse("2026-10-05T19:00:00Z");
        Instant endsA = Instant.parse("2026-10-05T21:00:00Z");

        TicketIssuedEvent eventPayload = new TicketIssuedEvent(
                ticketId,
                reservationId,
                userId,
                "alice@example.com",
                "Alice Smith",
                sessionA,
                eventId,
                startsA,
                endsA,
                null,
                seatId,
                new BigDecimal("120.00"),
                new BigDecimal("20.00"),
                new BigDecimal("100.00"),
                "SF-TKT-7788",
                "QRDATA",
                Instant.now()
        );

        EventEnvelope<TicketIssuedEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "TicketIssued",
                Instant.now(),
                "corr-123",
                null,
                ticketId.toString(),
                1,
                eventPayload
        );

        listener.handleTicketEvent(envelope);

        ArgumentCaptor<TicketIssuedEvent> captor = ArgumentCaptor.forClass(TicketIssuedEvent.class);
        verify(notificationService).sendTicketIssuedNotification(captor.capture());

        TicketIssuedEvent captured = captor.getValue();
        assertThat(captured.ticketId()).isEqualTo(ticketId);
        assertThat(captured.customerEmail()).isEqualTo("alice@example.com");
        assertThat(captured.ticketCode()).isEqualTo("SF-TKT-7788");
        assertThat(captured.price()).isEqualTo(new BigDecimal("120.00"));
        // P12-008 scenario F/A: the exact session-A identity and showing
        // snapshot pass through to the notification unchanged.
        assertThat(captured.eventSessionId()).isEqualTo(sessionA);
        assertThat(captured.sessionStartsAt()).isEqualTo(startsA);
        assertThat(captured.sessionEndsAt()).isEqualTo(endsA);
    }

    @Test
    @DisplayName("Should ignore unsupported ticket event types")
    void shouldIgnoreUnsupportedEventTypes() {
        EventEnvelope<String> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "TicketCancelled",
                Instant.now(),
                "corr-456",
                null,
                UUID.randomUUID().toString(),
                1,
                "payload"
        );

        listener.handleTicketEvent(envelope);

        verifyNoInteractions(notificationService);
    }
}
