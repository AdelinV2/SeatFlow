package com.seatflow.ticket.messaging.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TicketIssuedEvent(
    UUID ticketId,
    UUID reservationId,
    UUID paymentId,
    UUID userId,
    String customerEmail,
    String attendeeName,
    UUID eventId,
    UUID seatId,
    BigDecimal price,
    BigDecimal taxAmount,
    BigDecimal netAmount,
    String ticketCode,
    String status,
    Instant issuedAt
) {}
