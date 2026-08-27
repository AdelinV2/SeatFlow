package com.seatflow.realtime.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event received when a temporary seat hold expires")
public record ReservationExpiredEvent(
        UUID reservationId,
        UUID eventId,
        List<UUID> seatIds,
        String reason,
        Instant occurredAt
) implements DomainEvent {}
