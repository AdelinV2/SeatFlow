package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationHeldEvent(
        UUID reservationId,
        UUID eventId,
        UUID userId,
        String customerEmail,
        List<UUID> seatIds,
        Instant expiresAt,
        BigDecimal totalAmount,
        Instant occurredAt
) implements DomainEvent {
}
