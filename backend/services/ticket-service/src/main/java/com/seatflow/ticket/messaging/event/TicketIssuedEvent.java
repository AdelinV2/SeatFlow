package com.seatflow.ticket.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event published when a ticket is successfully issued")
public record TicketIssuedEvent(
    UUID ticketId,
    UUID reservationId,
    UUID userId,              // Nullable for guest purchases (ADR-001)
    String customerEmail,
    String attendeeName,
    UUID eventId,
    UUID seatId,
    BigDecimal price,         // Gross total price
    BigDecimal taxAmount,     // Tax / VAT portion (ADR-004)
    BigDecimal netAmount,     // Net base price (ADR-004)
    String ticketCode,
    String qrCodeData,
    Instant occurredAt
) implements DomainEvent {}
