package com.seatflow.notification.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event published when a digital ticket is issued")
public record TicketIssuedEvent(
        UUID ticketId,
        UUID reservationId,
        UUID userId,
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
) implements DomainEvent {
}
