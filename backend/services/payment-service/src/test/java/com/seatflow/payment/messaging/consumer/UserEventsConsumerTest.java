package com.seatflow.payment.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.seatflow.common.events.EventEnvelope;
import com.seatflow.payment.messaging.event.UserRegisteredEvent;
import com.seatflow.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserEventsConsumerTest {

    @Mock
    private PaymentService paymentService;

    private ObjectMapper objectMapper;
    private UserEventsConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule());
        var kafkaScope = mock(com.seatflow.common.observability.tracing.KafkaListenerTraceScope.class);
        org.mockito.Mockito.when(kafkaScope.open(any(), any(), any(), any())).thenReturn(mock(com.seatflow.common.observability.tracing.KafkaListenerTraceScope.class));
        consumer = new UserEventsConsumer(paymentService, objectMapper, kafkaScope);
    }

    @Test
    void shouldProcessUserRegisteredEventAndClaimPayments() throws Exception {
        UUID userId = UUID.randomUUID();
        String email = "guest@seatflow.com";
        UserRegisteredEvent payload = new UserRegisteredEvent(userId, email, Instant.now());
        EventEnvelope<UserRegisteredEvent> envelope = EventEnvelope.of(
                "UserRegistered",
                userId.toString(),
                UUID.randomUUID().toString(),
                payload
        );
        String json = objectMapper.writeValueAsString(envelope);

        when(paymentService.claimGuestPayments(userId, email)).thenReturn(2);

        consumer.handleUserEvents(json, "seatflow.user.events", 0, 100L);

        verify(paymentService, times(1)).claimGuestPayments(userId, email);
    }

    @Test
    void shouldIgnoreNonMatchingEventTypeSafely() {
        String json = """
                {
                    "eventId": "evt-123",
                    "eventType": "UserProfileUpdated",
                    "aggregateId": "agg-123",
                    "correlationId": "corr-123",
                    "version": 1,
                    "payload": {
                        "name": "New Name"
                    }
                }
                """;

        consumer.handleUserEvents(json, "seatflow.user.events", 0, 101L);

        verify(paymentService, never()).claimGuestPayments(any(), any());
    }

    @Test
    void shouldRethrowExceptionWhenPayloadIsInvalidJson() {
        String invalidJson = "invalid-not-json";

        assertThatThrownBy(() -> consumer.handleUserEvents(invalidJson, "seatflow.user.events", 0, 102L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error processing UserRegistered event");

        verify(paymentService, never()).claimGuestPayments(any(), any());
    }
}
