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

class UserEventsConsumerTest {

    private final ReservationService reservationService = mock(ReservationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new ParameterNamesModule())
            .registerModule(new JavaTimeModule());

    private UserEventsConsumer consumer() {
        return new UserEventsConsumer(reservationService, objectMapper);
    }

    @Test
    void processesUserRegisteredAndClaimsGuestReservations() throws Exception {
        UUID userId = UUID.randomUUID();
        String email = "guest@example.com";
        UserRegisteredEvent event = new UserRegisteredEvent(userId.toString(), email, "Guest", Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("UserRegistered", userId.toString(), UUID.randomUUID().toString(), event));

        consumer().listen(json, "seatflow.user.events", 0, 0);

        verify(reservationService).claimGuestReservations(userId, email);
    }

    @Test
    void ignoresPaymentCompletedEventOnUserTopic() throws Exception {
        PaymentCompletedEvent payload = new PaymentCompletedEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "x", UUID.randomUUID().toString(),
                new BigDecimal("1"), "USD", "pi", Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("PaymentCompleted", UUID.randomUUID().toString(), UUID.randomUUID().toString(), payload));

        consumer().listen(json, "seatflow.user.events", 0, 0);

        verify(reservationService, never()).claimGuestReservations(any(), any());
    }

    @Test
    void ignoresUnsupportedEventTypeWithoutError() throws Exception {
        PaymentCompletedEvent payload = new PaymentCompletedEvent(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "x", UUID.randomUUID().toString(),
                new BigDecimal("1"), "USD", "pi", Instant.now());
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of("SomethingElse", UUID.randomUUID().toString(), UUID.randomUUID().toString(), payload));

        consumer().listen(json, "seatflow.user.events", 0, 0);

        verify(reservationService, never()).claimGuestReservations(any(), any());
    }

    @Test
    void rethrowsOnInvalidJson() {
        assertThatThrownBy(() -> consumer().listen("not-json", "seatflow.user.events", 0, 0))
                .isInstanceOf(RuntimeException.class);
    }
}
