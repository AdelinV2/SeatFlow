package com.seatflow.realtime.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Event received when a temporary seat hold expires")
public record ReservationExpiredEvent(
        UUID reservationId,
        UUID eventSessionId,
        UUID eventId,
        List<UUID> seatIds,
        String reason,
        Instant occurredAt
) implements DomainEvent {}
