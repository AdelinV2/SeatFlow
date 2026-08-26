package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Event published when a reservation hold is confirmed after successful payment")
public record ReservationConfirmedEvent(
        UUID reservationId,
        UUID eventId,
        UUID userId,
        String customerEmail,
        List<UUID> seatIds,
        BigDecimal totalAmount,
        UUID paymentId,
        Instant occurredAt
) implements DomainEvent {
}
