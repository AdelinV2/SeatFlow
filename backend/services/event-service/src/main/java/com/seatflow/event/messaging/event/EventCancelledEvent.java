package com.seatflow.event.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Emitted when an event is cancelled")
public record EventCancelledEvent(
        @Schema(description = "Event UUID") UUID eventId,
        @Schema(description = "Owning venue UUID") UUID venueId,
        @Schema(description = "Event title") String title,
        @Schema(description = "UTC start time") Instant eventDate,
        @Schema(description = "Occurrence timestamp") Instant occurredAt
) implements DomainEvent {}
