package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.TicketIssuedEvent;
import com.seatflow.realtime.service.SeatStatusBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketEventListenerTest {

    record DummyEvent(String message) implements DomainEvent {}

    @Mock
    private SeatStatusBroadcaster seatStatusBroadcaster;

    private ObjectMapper objectMapper;
    private TicketEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new TicketEventListener(seatStatusBroadcaster, objectMapper);
    }

    @Test
    @DisplayName("Should process TicketIssued event and broadcast SOLD status for single seat")
    void handleTicketEvent_TicketIssued_BroadcastsSold() {
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        TicketIssuedEvent payload = new TicketIssuedEvent(
                ticketId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "customer@seatflow.com",
                "Alex Smith",
                eventId,
                seatId,
                BigDecimal.valueOf(75.00),
                BigDecimal.valueOf(14.25),
                BigDecimal.valueOf(60.75),
                "SF-TKT-1234-ABCD",
                "SF://TKT/1234/SIGN",
                Instant.now()
        );

        EventEnvelope<TicketIssuedEvent> envelope = EventEnvelope.of(
                "TicketIssued",
                ticketId.toString(),
                "corr-ticket-1",
                payload
        );

        listener.handleTicketEvent(envelope);

        verify(seatStatusBroadcaster).broadcastSeatStatus(eventId, List.of(seatId), SeatStatus.SOLD, null);
    }

    @Test
    @DisplayName("Should silently ignore non-TicketIssued event types on ticket topic")
    void handleTicketEvent_OtherEventType_IgnoresEvent() {
        EventEnvelope<DummyEvent> envelope = EventEnvelope.of(
                "TicketValidated",
                UUID.randomUUID().toString(),
                "corr-ticket-2",
                new DummyEvent("generic-data")
        );

        listener.handleTicketEvent(envelope);

        verifyNoInteractions(seatStatusBroadcaster);
    }

    @Test
    @DisplayName("Should silently return when envelope is null or eventType is null")
    void handleTicketEvent_NullEnvelope_IgnoresGracefully() {
        listener.handleTicketEvent(null);
        listener.handleTicketEvent(EventEnvelope.of(null, "id", "corr", null));

        verifyNoInteractions(seatStatusBroadcaster);
    }
}
