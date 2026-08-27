package com.seatflow.realtime.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event received when seats are temporarily held for a reservation")
public record ReservationHeldEvent(
        UUID reservationId,
        UUID eventId,
        UUID userId,              // Nullable for guest checkouts (ADR-001)
        String customerEmail,
        List<UUID> seatIds,
        Instant expiresAt,
        BigDecimal totalAmount,
        Instant occurredAt
) implements DomainEvent {}
