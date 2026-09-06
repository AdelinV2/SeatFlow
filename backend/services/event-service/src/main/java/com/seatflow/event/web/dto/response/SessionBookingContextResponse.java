package com.seatflow.event.web.dto.response;

import com.seatflow.event.model.enums.EventSessionStatus;
import com.seatflow.event.model.enums.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Server-derived booking facts for one event session, consumed by reservation-service")
public record SessionBookingContextResponse(

    @Schema(description = "Selected session UUID") UUID eventSessionId,
    @Schema(description = "Parent event UUID, derived server-side from the session") UUID eventId,
    @Schema(description = "Parent event lifecycle status") EventStatus eventStatus,
    @Schema(description = "Session lifecycle status") EventSessionStatus sessionStatus,
    @Schema(description = "Session start instant") Instant startsAt,
    @Schema(description = "Session end instant") Instant endsAt,
    @Schema(description = "Optional instant from which booking sales are open") Instant saleStartsAt,
    @Schema(description = "Optional instant at which booking sales close") Instant saleEndsAt,
    @Schema(description = "Owning venue UUID (sessions inherit the event venue; events carry no hall)") UUID venueId

) {}
