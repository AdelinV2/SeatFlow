package com.seatflow.ticket.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event received when a payment is successfully processed")
public record PaymentCompletedEvent(
    UUID paymentId,
    UUID reservationId,
    UUID userId,              // Nullable for guest checkouts (ADR-001)
    String customerEmail,
    UUID eventId,
    BigDecimal amount,        // Total gross amount charged
    BigDecimal taxAmount,     // Tax portion computed by Stripe Tax (ADR-004)
    BigDecimal netAmount,     // Net merchant revenue portion
    String currency,
    String stripePaymentId,
    Instant occurredAt
) implements DomainEvent {}
