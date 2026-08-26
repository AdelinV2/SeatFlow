package com.seatflow.reservation.messaging.event;

import com.seatflow.common.events.DomainEvent;

import java.time.Instant;

public record UserRegisteredEvent(
        String userId,
        String email,
        String name,
        Instant registeredAt
) implements DomainEvent {
}
