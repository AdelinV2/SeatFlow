package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentCompletedEvent(
        String paymentId,
        String reservationId,
        String userId,
        String customerEmail,
        String eventId,
        BigDecimal amount,
        String currency,
        String stripePaymentId,
        Instant occurredAt
) implements DomainEvent {
}
