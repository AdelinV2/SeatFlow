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
class ReservationEventListenerTest {

    record DummyEvent(String message) implements DomainEvent {}

    @Mock
    private RealtimeFanOutPublisher realtimeFanOutPublisher;

    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private ReservationEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        listener = new ReservationEventListener(realtimeFanOutPublisher, objectMapper, mock(), meterRegistry);
    }

    @Test
    @DisplayName("Should process ReservationHeld event and broadcast HELD status with expiration timestamp")
    void handleReservationEvent_ReservationHeld_BroadcastsHeld() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        Instant expiresAt = Instant.now().plusSeconds(900);

        ReservationHeldEvent payload = new ReservationHeldEvent(
                reservationId,
                eventSessionId,
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

        ArgumentCaptor<SeatStatusUpdateMessage> captor = ArgumentCaptor.forClass(SeatStatusUpdateMessage.class);
        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), captor.capture());
        assertEquals(eventSessionId, captor.getValue().eventSessionId());
        assertEquals(eventId, captor.getValue().eventId());
        assertEquals(seatIds, captor.getValue().seatIds());
        assertEquals(SeatStatus.HELD, captor.getValue().status());
    }

    @Test
    @DisplayName("Should process ReservationConfirmed event and broadcast SOLD status")
    void handleReservationEvent_ReservationConfirmed_BroadcastsSold() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        ReservationConfirmedEvent payload = new ReservationConfirmedEvent(
                reservationId,
                eventSessionId,
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

        ArgumentCaptor<SeatStatusUpdateMessage> captor = ArgumentCaptor.forClass(SeatStatusUpdateMessage.class);
        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), captor.capture());
        assertEquals(eventSessionId, captor.getValue().eventSessionId());
        assertEquals(SeatStatus.SOLD, captor.getValue().status());
    }

    @Test
    @DisplayName("Should process ReservationExpired event and broadcast AVAILABLE status")
    void handleReservationEvent_ReservationExpired_BroadcastsAvailable() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID());

        ReservationExpiredEvent payload = new ReservationExpiredEvent(
                reservationId,
                eventSessionId,
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

        ArgumentCaptor<SeatStatusUpdateMessage> captor = ArgumentCaptor.forClass(SeatStatusUpdateMessage.class);
        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), captor.capture());
        assertEquals(eventSessionId, captor.getValue().eventSessionId());
        assertEquals(SeatStatus.AVAILABLE, captor.getValue().status());
    }

    @Test
    @DisplayName("Should process ReservationCancelled event and broadcast AVAILABLE status")
    void handleReservationEvent_ReservationCancelled_BroadcastsAvailable() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        ReservationCancelledEvent payload = new ReservationCancelledEvent(
                reservationId,
                eventSessionId,
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

        ArgumentCaptor<SeatStatusUpdateMessage> captor = ArgumentCaptor.forClass(SeatStatusUpdateMessage.class);
        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), captor.capture());
        assertEquals(eventSessionId, captor.getValue().eventSessionId());
        assertEquals(SeatStatus.AVAILABLE, captor.getValue().status());
    }

    @Test
    @DisplayName("Should discard legacy event-only message without inferring a session")
    void handleReservationEvent_MissingEventSessionId_DiscardsWithoutPublish() {
        UUID reservationId = UUID.randomUUID();

        ReservationHeldEvent payload = new ReservationHeldEvent(
                reservationId,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "customer@seatflow.com",
                List.of(UUID.randomUUID()),
                Instant.now().plusSeconds(900),
                BigDecimal.valueOf(150.00),
                Instant.now()
        );

        EventEnvelope<ReservationHeldEvent> envelope = EventEnvelope.of(
                "ReservationHeldEvent",
                reservationId.toString(),
                "corr-legacy-1",
                payload
        );

        listener.handleReservationEvent(envelope);

        verifyNoInteractions(realtimeFanOutPublisher);
    }

    @Test
    @DisplayName("Duplicate Kafka delivery repeats the same public seat state without mutating authority")
    void handleReservationEvent_DuplicateDelivery_RepeatsSamePublicState() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID());

        ReservationHeldEvent payload = new ReservationHeldEvent(
                reservationId,
                eventSessionId,
                eventId,
                UUID.randomUUID(),
                "customer@seatflow.com",
                seatIds,
                Instant.now().plusSeconds(900),
                BigDecimal.valueOf(50.00),
                Instant.now()
        );

        EventEnvelope<ReservationHeldEvent> envelope = EventEnvelope.of(
                "ReservationHeld",
                reservationId.toString(),
                "corr-dup-1",
                payload
        );

        listener.handleReservationEvent(envelope);
        listener.handleReservationEvent(envelope);

        ArgumentCaptor<SeatStatusUpdateMessage> captor = ArgumentCaptor.forClass(SeatStatusUpdateMessage.class);
        verify(realtimeFanOutPublisher, times(2)).publish(eq(envelope.eventId()), captor.capture());
        SeatStatusUpdateMessage first = captor.getAllValues().get(0);
        SeatStatusUpdateMessage second = captor.getAllValues().get(1);
        // Each publish stamps Instant.now() by design, so compare the repeated public seat
        // state field-wise instead of requiring identical broadcast timestamps.
        assertEquals(first.eventSessionId(), second.eventSessionId());
        assertEquals(first.eventId(), second.eventId());
        assertEquals(first.seatIds(), second.seatIds());
        assertEquals(first.status(), second.status());
        assertEquals(first.holdExpiresAt(), second.holdExpiresAt());
        assertEquals(eventSessionId, first.eventSessionId());
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

    @Test
    @DisplayName("Should tolerate unknown producer fields (session schedule metadata) in raw Kafka Map payload")
    void handleReservationEvent_RawMapWithUnknownFields_BroadcastsSold() {
        UUID eventSessionId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        java.util.Map<String, Object> rawPayload = new java.util.HashMap<>();
        rawPayload.put("reservationId", reservationId.toString());
        rawPayload.put("eventSessionId", eventSessionId.toString());
        rawPayload.put("eventId", eventId.toString());
        rawPayload.put("userId", UUID.randomUUID().toString());
        rawPayload.put("customerEmail", "customer@seatflow.com");
        rawPayload.put("seatIds", java.util.List.of(seatId.toString()));
        rawPayload.put("totalAmount", 150.00);
        rawPayload.put("paymentId", UUID.randomUUID().toString());
        rawPayload.put("sessionStartsAt", "2026-09-10T18:00:00Z");
        rawPayload.put("sessionEndsAt", "2026-09-10T20:00:00Z");
        rawPayload.put("sessionTimezone", "Europe/Berlin");
        rawPayload.put("occurredAt", "2026-09-05T10:00:00Z");

        EventEnvelope<java.util.Map<String, Object>> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "ReservationConfirmed",
                Instant.now(),
                "corr-raw-1",
                null,
                reservationId.toString(),
                com.seatflow.common.events.EventEnvelope.CURRENT_VERSION,
                rawPayload
        );

        listener.handleReservationEvent(envelope);

        ArgumentCaptor<SeatStatusUpdateMessage> captor = ArgumentCaptor.forClass(SeatStatusUpdateMessage.class);
        verify(realtimeFanOutPublisher).publish(eq(envelope.eventId()), captor.capture());
        assertEquals(eventSessionId, captor.getValue().eventSessionId());
        assertEquals(eventId, captor.getValue().eventId());
        assertEquals(java.util.List.of(seatId), captor.getValue().seatIds());
        assertEquals(SeatStatus.SOLD, captor.getValue().status());
    }
}
