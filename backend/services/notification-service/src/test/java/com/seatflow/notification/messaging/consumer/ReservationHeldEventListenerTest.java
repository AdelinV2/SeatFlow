package com.seatflow.notification.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.notification.messaging.event.ReservationHeldEvent;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationHeldEventListenerTest {

    @Mock
    private NotificationService notificationService;

    private ObjectMapper objectMapper;
    private ReservationHeldEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        listener = new ReservationHeldEventListener(notificationService, objectMapper);
    }

    @Test
    @DisplayName("Should unwrap ReservationHeld envelope and delegate to NotificationService")
    void shouldProcessReservationHeldEvent() {
        UUID resId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<UUID> seatIds = List.of(UUID.randomUUID(), UUID.randomUUID());

        ReservationHeldEvent eventPayload = new ReservationHeldEvent(
                resId,
                eventId,
                userId,
                "dave@example.com",
                seatIds,
                Instant.now().plusSeconds(900),
                new BigDecimal("150.00"),
                Instant.now()
        );

        EventEnvelope<ReservationHeldEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "ReservationHeld",
                Instant.now(),
                "corr-res-1",
                null,
                resId.toString(),
                1,
                eventPayload
        );

        listener.handleReservationEvent(envelope);

        ArgumentCaptor<ReservationHeldEvent> captor = ArgumentCaptor.forClass(ReservationHeldEvent.class);
        verify(notificationService).sendReservationHeldNotification(captor.capture());

        ReservationHeldEvent captured = captor.getValue();
        assertThat(captured.reservationId()).isEqualTo(resId);
        assertThat(captured.customerEmail()).isEqualTo("dave@example.com");
        assertThat(captured.seatIds()).hasSize(2);
        assertThat(captured.totalAmount()).isEqualTo(new BigDecimal("150.00"));
    }

    @Test
    @DisplayName("Should ignore unsupported reservation event types")
    void shouldIgnoreUnsupportedReservationEvent() {
        EventEnvelope<String> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "ReservationExpired",
                Instant.now(),
                "corr-res-2",
                null,
                UUID.randomUUID().toString(),
                1,
                "payload"
        );

        listener.handleReservationEvent(envelope);

        verifyNoInteractions(notificationService);
    }
}
