package com.seatflow.user.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Domain event payload published when a user is registered via JIT provisioning")
public record UserRegisteredEvent(

    @Schema(description = "Internal user UUID")
    UUID userId,

    @Schema(description = "User email address")
    String email,

    @Schema(description = "Registration timestamp")
    Instant registeredAt

) implements DomainEvent {}
