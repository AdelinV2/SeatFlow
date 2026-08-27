package com.seatflow.payment.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event published when a payment succeeds")
public record PaymentCompletedEvent(
    UUID paymentId,
    UUID reservationId,
    UUID userId,
    String customerEmail,
    UUID eventId,
    BigDecimal amount,
    BigDecimal taxAmount,
    BigDecimal netAmount,
    String currency,
    String stripePaymentId,
    Instant occurredAt
) implements DomainEvent {}
