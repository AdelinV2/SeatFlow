package com.seatflow.reservation.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.reservation.messaging.event.PaymentCompletedEvent;
import com.seatflow.reservation.messaging.event.UserRegisteredEvent;
import com.seatflow.reservation.service.ReservationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentEventsConsumerTest {

    private final ReservationService reservationService = mock(ReservationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new ParameterNamesModule())
            .registerModule(new JavaTimeModule());
    private final com.seatflow.common.observability.tracing.KafkaListenerTraceScope kafkaScope =
            mock(com.seatflow.common.observability.tracing.KafkaListenerTraceScope.class);

    private PaymentEventsConsumer consumer() {
        org.mockito.Mockito.when(kafkaScope.open(any(), any(), any(), any())).thenReturn(mock(com.seatflow.common.observability.tracing.KafkaListenerTraceScope.class));
        return new PaymentEventsConsumer(reservationService, objectMapper, kafkaScope);
    }

    @Test
    void processesPaymentCompletedAndConfirmsReservation() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                paymentId.toString(), reservationId.toString(), UUID.randomUUID().toString(),
                "guest@example.com", UUID.randomUUID().toString(), new BigDecimal("30.00"), "USD",
                "pi_1", Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("PaymentCompleted", reservationId.toString(), UUID.randomUUID().toString(), event));

        consumer().listen(json, "seatflow.payment.events", 0, 0);

        verify(reservationService).confirmReservation(reservationId, paymentId);
    }

    @Test
    void ignoresUserRegisteredEventOnPaymentTopic() throws Exception {
        UserRegisteredEvent event = new UserRegisteredEvent(UUID.randomUUID().toString(), "guest@example.com", "Guest", Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("UserRegistered", UUID.randomUUID().toString(), UUID.randomUUID().toString(), event));

        consumer().listen(json, "seatflow.payment.events", 0, 0);

        verify(reservationService, never()).confirmReservation(any(), any());
    }

    @Test
    void ignoresUnsupportedEventTypeWithoutError() throws Exception {
        PaymentCompletedEvent payload = new PaymentCompletedEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "x", UUID.randomUUID().toString(),
                new BigDecimal("1"), "USD", "pi", Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("SomethingElse", UUID.randomUUID().toString(), UUID.randomUUID().toString(), payload));

        consumer().listen(json, "seatflow.payment.events", 0, 0);

        verify(reservationService, never()).confirmReservation(any(), any());
    }

    @Test
    void rethrowsOnInvalidJson() {
        assertThatThrownBy(() -> consumer().listen("not-json", "seatflow.payment.events", 0, 0))
                .isInstanceOf(RuntimeException.class);
    }
}
