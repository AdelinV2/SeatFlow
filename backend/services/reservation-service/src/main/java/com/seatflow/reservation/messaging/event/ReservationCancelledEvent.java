package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationCancelledEvent(
        UUID reservationId,
        UUID eventId,
        UUID userId,
        String customerEmail,
        List<UUID> seatIds,
        Instant occurredAt
) implements DomainEvent {
}
