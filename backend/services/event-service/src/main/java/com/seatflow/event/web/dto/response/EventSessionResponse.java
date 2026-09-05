package com.seatflow.event.web.dto.response;

import com.seatflow.event.model.enums.EventSessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "One concrete scheduled showing of an event")
public record EventSessionResponse(

    @Schema(description = "Session UUID") UUID id,
    @Schema(description = "Parent event UUID") UUID eventId,
    @Schema(description = "Session start instant") Instant startsAt,
    @Schema(description = "Session end instant") Instant endsAt,
    @Schema(description = "Optional instant from which booking sales are open") Instant saleStartsAt,
    @Schema(description = "Optional instant at which booking sales close") Instant saleEndsAt,
    @Schema(description = "Session lifecycle status") EventSessionStatus status,
    @Schema(description = "Optional IANA display timezone") String timezone,
    @Schema(description = "Creation timestamp") Instant createdAt,
    @Schema(description = "Last update timestamp") Instant updatedAt

) {}
