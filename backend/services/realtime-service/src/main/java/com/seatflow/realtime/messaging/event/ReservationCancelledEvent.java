package com.seatflow.realtime.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event received when a reservation is manually cancelled")
public record ReservationCancelledEvent(
        UUID reservationId,
        UUID eventId,
        UUID userId,
        String customerEmail,
        List<UUID> seatIds,
        Instant occurredAt
) implements DomainEvent {}
