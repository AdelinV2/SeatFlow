package com.seatflow.notification.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.notification.messaging.event.PaymentFailedEvent;
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
class PaymentFailedEventListenerTest {

    @Mock
    private NotificationService notificationService;

    private ObjectMapper objectMapper;
    private PaymentFailedEventListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        listener = new PaymentFailedEventListener(notificationService, objectMapper, mock());
    }

    @Test
    @DisplayName("Should unwrap PaymentFailed envelope and delegate to NotificationService")
    void shouldProcessPaymentFailedEvent() {
        UUID paymentId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();

        PaymentFailedEvent eventPayload = new PaymentFailedEvent(
                paymentId,
                resId,
                UUID.randomUUID(),
                "bob@example.com",
                UUID.randomUUID(),
                new BigDecimal("60.00"),
                "USD",
                "pi_failed_999",
                "Card expired",
                Instant.now()
        );

        EventEnvelope<PaymentFailedEvent> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "PaymentFailed",
                Instant.now(),
                "corr-pay-1",
                null,
                paymentId.toString(),
                1,
                eventPayload
        );

        listener.handlePaymentEvent(envelope);

        ArgumentCaptor<PaymentFailedEvent> captor = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(notificationService).sendPaymentFailedNotification(captor.capture());

        PaymentFailedEvent captured = captor.getValue();
        assertThat(captured.paymentId()).isEqualTo(paymentId);
        assertThat(captured.customerEmail()).isEqualTo("bob@example.com");
        assertThat(captured.failureReason()).isEqualTo("Card expired");
    }

    @Test
    @DisplayName("Should ignore unsupported payment event types")
    void shouldIgnoreUnsupportedPaymentEvent() {
        EventEnvelope<String> envelope = new EventEnvelope<>(
                UUID.randomUUID().toString(),
                "PaymentCompleted",
                Instant.now(),
                "corr-pay-2",
                null,
                UUID.randomUUID().toString(),
                1,
                "payload"
        );

        listener.handlePaymentEvent(envelope);

        verifyNoInteractions(notificationService);
    }
}
