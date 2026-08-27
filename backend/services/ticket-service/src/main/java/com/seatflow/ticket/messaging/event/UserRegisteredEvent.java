package com.seatflow.ticket.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Event received when a user registers an account")
public record UserRegisteredEvent(
    UUID userId,
    String email,
    Instant registeredAt
) implements DomainEvent {}
