package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event published when a reservation hold exceeds the 15-minute expiration window and is released")
public record ReservationExpiredEvent(
        UUID reservationId,
        UUID eventId,
        List<UUID> seatIds,
        String reason,
        Instant occurredAt
) implements DomainEvent {
}
