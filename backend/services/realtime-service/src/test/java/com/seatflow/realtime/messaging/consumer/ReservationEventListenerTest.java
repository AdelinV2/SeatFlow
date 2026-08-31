package com.seatflow.realtime.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.DomainEvent;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.realtime.enums.SeatStatus;
import com.seatflow.realtime.messaging.event.ReservationCancelledEvent;
import com.seatflow.realtime.messaging.event.ReservationConfirmedEvent;
import com.seatflow.realtime.messaging.event.ReservationExpiredEvent;
import com.seatflow.realtime.messaging.event.ReservationHeldEvent;
import com.seatflow.realtime.dto.SeatStatusUpdateMessage;
import com.seatflow.realtime.service.RealtimeFanOutPublisher;
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
class ReservationEventListenerTest {

    record DummyEvent(String message) implements DomainEvent {}

    @Mock
    private RealtimeFanOutPublisher realtimeFanOutPublisher;

    private ObjectMapper objectMapper;
    private ReservationEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new ReservationEventListener(realtimeFanOutPublisher, objectMapper);
    }

    @Test
    @DisplayName("Should process ReservationHeld event and broadcast HELD status with expiration timestamp")
    void handleReservationEvent_ReservationHeld_BroadcastsHeld() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        Instant expiresAt = Instant.now().plusSeconds(900);

        ReservationHeldEvent payload = new ReservationHeldEvent(
                reservationId,
                eventId,
                UUID.randomUUID(),
                "customer@seatflow.com",
                seatIds,
                expiresAt,
                BigDecimal.valueOf(150.00),
                Instant.now()
        );

        EventEnvelope<ReservationHeldEvent> envelope = EventEnvelope.of(
                "ReservationHeldEvent",
                reservationId.toString(),
                "corr-1234",
                payload
        );

        listener.handleReservationEvent(envelope);

        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), any(SeatStatusUpdateMessage.class));
    }

    @Test
    @DisplayName("Should process ReservationConfirmed event and broadcast SOLD status")
    void handleReservationEvent_ReservationConfirmed_BroadcastsSold() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        ReservationConfirmedEvent payload = new ReservationConfirmedEvent(
                reservationId,
                eventId,
                UUID.randomUUID(),
                "customer@seatflow.com",
                seatIds,
                BigDecimal.valueOf(150.00),
                UUID.randomUUID(),
                Instant.now()
        );

        EventEnvelope<ReservationConfirmedEvent> envelope = EventEnvelope.of(
                "ReservationConfirmedEvent",
                reservationId.toString(),
                "corr-9999",
                payload
        );

        listener.handleReservationEvent(envelope);

        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), any(SeatStatusUpdateMessage.class));
    }

    @Test
    @DisplayName("Should process ReservationExpired event and broadcast AVAILABLE status")
    void handleReservationEvent_ReservationExpired_BroadcastsAvailable() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID());

        ReservationExpiredEvent payload = new ReservationExpiredEvent(
                reservationId,
                eventId,
                seatIds,
                "HOLD_TIMEOUT_EXCEEDED",
                Instant.now()
        );

        EventEnvelope<ReservationExpiredEvent> envelope = EventEnvelope.of(
                "ReservationExpiredEvent",
                reservationId.toString(),
                "corr-5678",
                payload
        );

        listener.handleReservationEvent(envelope);

        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), any(SeatStatusUpdateMessage.class));
    }

    @Test
    @DisplayName("Should process ReservationCancelled event and broadcast AVAILABLE status")
    void handleReservationEvent_ReservationCancelled_BroadcastsAvailable() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        ReservationCancelledEvent payload = new ReservationCancelledEvent(
                reservationId,
                eventId,
                UUID.randomUUID(),
                "customer@seatflow.com",
                seatIds,
                Instant.now()
        );

        EventEnvelope<ReservationCancelledEvent> envelope = EventEnvelope.of(
                "ReservationCancelledEvent",
                reservationId.toString(),
                "corr-9999",
                payload
        );

        listener.handleReservationEvent(envelope);

        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), any(SeatStatusUpdateMessage.class));
    }

    @Test
    @DisplayName("Should silently ignore unrecognized reservation event types")
    void handleReservationEvent_UnrecognizedType_IgnoresEvent() {
        EventEnvelope<DummyEvent> envelope = EventEnvelope.of(
                "SomeUnknownEvent",
                UUID.randomUUID().toString(),
                "corr-0000",
                new DummyEvent("generic-payload")
        );

        listener.handleReservationEvent(envelope);

        verifyNoInteractions(realtimeFanOutPublisher);
    }

    @Test
    @DisplayName("Should silently return when envelope is null or eventType is null")
    void handleReservationEvent_NullEnvelope_IgnoresGracefully() {
        listener.handleReservationEvent(null);
        listener.handleReservationEvent(EventEnvelope.of(null, "id", "corr", null));

        verifyNoInteractions(realtimeFanOutPublisher);
    }
}
