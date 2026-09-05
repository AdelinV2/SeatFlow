package com.seatflow.event.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Domain event published when an event lifecycle completes")
public record EventCompletedEvent(
    @Schema(description = "Event unique identifier") UUID eventId,
    @Schema(description = "Associated venue identifier") UUID venueId,
    @Schema(description = "Event title") String title,
    // P12-007: legacy eventDate removed. Showing schedule lives on EventSession.
    @Schema(description = "Completion timestamp") Instant occurredAt
) implements DomainEvent {}
