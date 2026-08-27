package com.seatflow.notification.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event published when seats are temporarily placed on hold")
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
