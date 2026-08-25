package com.seatflow.seatmap.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Domain event published when a new venue is created")
public record VenueCreatedEvent(
    @Schema(description = "Venue UUID") UUID venueId,
    @Schema(description = "Venue name") String name,
    @Schema(description = "City") String city,
    @Schema(description = "Total capacity") Integer capacity,
    @Schema(description = "Creation timestamp") Instant createdAt
) implements DomainEvent {}
