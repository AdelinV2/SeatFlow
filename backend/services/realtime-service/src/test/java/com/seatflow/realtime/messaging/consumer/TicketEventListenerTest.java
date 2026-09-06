package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.TicketIssuedEvent;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.service.RealtimeFanOutPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketEventListenerTest {

    record DummyEvent(String message) implements DomainEvent {}

    @Mock
    private RealtimeFanOutPublisher realtimeFanOutPublisher;

    private ObjectMapper objectMapper;
    private TicketEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new TicketEventListener(realtimeFanOutPublisher, objectMapper, mock(),
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Should process TicketIssued event and broadcast SOLD status for single seat")
    void handleTicketEvent_TicketIssued_BroadcastsSold() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        TicketIssuedEvent payload = new TicketIssuedEvent(
                ticketId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "customer@seatflow.com",
                "Alex Smith",
                eventSessionId,
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

        ArgumentCaptor<SeatStatusUpdateMessage> captor = ArgumentCaptor.forClass(SeatStatusUpdateMessage.class);
        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), captor.capture());
        assertEquals(eventSessionId, captor.getValue().eventSessionId());
        assertEquals(eventId, captor.getValue().eventId());
        assertEquals(List.of(seatId), captor.getValue().seatIds());
        assertEquals(SeatStatus.SOLD, captor.getValue().status());
    }

    @Test
    @DisplayName("Should discard legacy event-only ticket message without inferring a session")
    void handleTicketEvent_MissingEventSessionId_DiscardsWithoutPublish() {
        UUID ticketId = UUID.randomUUID();

        TicketIssuedEvent payload = new TicketIssuedEvent(
                ticketId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "customer@seatflow.com",
                "Alex Smith",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
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
                "corr-ticket-legacy",
                payload
        );

        listener.handleTicketEvent(envelope);

        verifyNoInteractions(realtimeFanOutPublisher);
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

        verifyNoInteractions(realtimeFanOutPublisher);
    }

    @Test
    @DisplayName("Should silently return when envelope is null or eventType is null")
    void handleTicketEvent_NullEnvelope_IgnoresGracefully() {
        listener.handleTicketEvent(null);
        listener.handleTicketEvent(EventEnvelope.of(null, "id", "corr", null));

        verifyNoInteractions(realtimeFanOutPublisher);
    }

    @Test
    @DisplayName("Should tolerate unknown producer fields (session schedule metadata) in raw Kafka Map payload")
    void handleTicketEvent_RawMapWithUnknownFields_BroadcastsSold() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        java.util.Map<String, Object> rawPayload = new java.util.HashMap<>();
        rawPayload.put("ticketId", ticketId.toString());
        rawPayload.put("reservationId", UUID.randomUUID().toString());
        rawPayload.put("userId", UUID.randomUUID().toString());
        rawPayload.put("customerEmail", "customer@seatflow.com");
        rawPayload.put("attendeeName", "Alex Smith");
        rawPayload.put("eventSessionId", eventSessionId.toString());
        rawPayload.put("eventId", eventId.toString());
        rawPayload.put("sessionStartsAt", "2026-09-10T18:00:00Z");
        rawPayload.put("sessionEndsAt", "2026-09-10T20:00:00Z");
        rawPayload.put("sessionTimezone", "Europe/Berlin");
        rawPayload.put("seatId", seatId.toString());
        rawPayload.put("price", 75.00);
        rawPayload.put("taxAmount", 14.25);
        rawPayload.put("netAmount", 60.75);
        rawPayload.put("ticketCode", "SF-TKT-1234-ABCD");
        rawPayload.put("qrCodeData", "SF://TKT/1234/SIGN");
        rawPayload.put("occurredAt", "2026-09-05T10:00:00Z");

        EventEnvelope<java.util.Map<String, Object>> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "TicketIssued",
                Instant.now(),
                "corr-ticket-raw-1",
                null,
                ticketId.toString(),
                com.seatflow.common.events.EventEnvelope.CURRENT_VERSION,
                rawPayload
        );

        listener.handleTicketEvent(envelope);

        ArgumentCaptor<SeatStatusUpdateMessage> captor = ArgumentCaptor.forClass(SeatStatusUpdateMessage.class);
        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), captor.capture());
        assertEquals(eventSessionId, captor.getValue().eventSessionId());
        assertEquals(eventId, captor.getValue().eventId());
        assertEquals(List.of(seatId), captor.getValue().seatIds());
        assertEquals(SeatStatus.SOLD, captor.getValue().status());
    }
}
