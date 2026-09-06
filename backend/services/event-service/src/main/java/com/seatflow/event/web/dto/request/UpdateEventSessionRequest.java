package com.seatflow.event.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(description = "Request body for updating the mutable schedule fields of an unlocked event session")
public record UpdateEventSessionRequest(

    @Schema(description = "Session start instant (offset-aware)", example = "2027-05-01T19:30:00Z",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Session start time is required")
    Instant startsAt,

    @Schema(description = "Session end instant (offset-aware, must be after startsAt)", example = "2027-05-01T21:30:00Z",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Session end time is required")
    Instant endsAt,

    @Schema(description = "Optional instant from which booking sales are open", example = "2027-04-01T09:00:00Z")
    Instant saleStartsAt,

    @Schema(description = "Optional instant at which booking sales close (must be on or before startsAt)",
            example = "2027-05-01T19:30:00Z")
    Instant saleEndsAt,

    @Schema(description = "Optional IANA display timezone, e.g. Europe/Berlin", example = "Europe/Berlin")
    @Size(max = 64, message = "Timezone must not exceed 64 characters")
    String timezone

) {}
