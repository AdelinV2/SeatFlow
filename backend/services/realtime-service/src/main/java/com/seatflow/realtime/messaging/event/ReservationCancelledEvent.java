package com.seatflow.realtime.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Event received when a reservation is manually cancelled")
public record ReservationCancelledEvent(
        UUID reservationId,
        UUID eventSessionId,
        UUID eventId,
        UUID userId,
        String customerEmail,
        List<UUID> seatIds,
        Instant occurredAt
) implements DomainEvent {}
