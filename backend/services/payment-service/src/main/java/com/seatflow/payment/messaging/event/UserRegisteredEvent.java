package com.seatflow.payment.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Inbound event published by user-service upon user registration")
public record UserRegisteredEvent(
    UUID userId,
    String email,
    Instant registeredAt
) implements DomainEvent {}
