package com.seatflow.realtime.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event received when a digital ticket is generated and issued for a seat")
public record TicketIssuedEvent(
        UUID ticketId,
        UUID reservationId,
        UUID userId,              // Nullable for guest checkouts (ADR-001)
        String customerEmail,
        String attendeeName,
        UUID eventId,
        UUID seatId,
        BigDecimal price,
        BigDecimal taxAmount,
        BigDecimal netAmount,
        String ticketCode,
        String qrCodeData,
        Instant occurredAt
) implements DomainEvent {}
