package com.seatflow.seatmap.messaging.event;

import com.seatflow.common.events.DomainEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Domain event published when a new venue section is created with seats")
public record VenueSectionCreatedEvent(
    @Schema(description = "Section UUID") UUID sectionId,
    @Schema(description = "Parent venue UUID") UUID venueId,
    @Schema(description = "Section name") String name,
    @Schema(description = "Number of rows") Integer rowCount,
    @Schema(description = "Number of columns") Integer colCount,
    @Schema(description = "Total seats generated") Integer totalSeats,
    @Schema(description = "Creation timestamp") Instant createdAt
) implements DomainEvent {}
