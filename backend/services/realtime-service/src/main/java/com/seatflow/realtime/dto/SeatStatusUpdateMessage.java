package com.seatflow.realtime.dto;

import com.seatflow.realtime.enums.SeatStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Schema(description = "STOMP broadcast payload for seat status updates on an event")
public record SeatStatusUpdateMessage(
        @Schema(description = "Event ID", example = "223e4567-e89b-12d3-a456-426614174000")
        UUID eventId,

        @Schema(description = "List of affected seat IDs")
        List<UUID> seatIds,

        @Schema(description = "Updated seat status", example = "HELD")
        SeatStatus status,

        @Schema(description = "Timestamp when the status update occurred")
        Instant timestamp,

        @Schema(description = "Expiration timestamp for HELD status (null for AVAILABLE or SOLD)")
        Instant holdExpiresAt
) {
    public static SeatStatusUpdateMessage of(
            UUID eventId,
            List<UUID> seatIds,
            SeatStatus status,
            Instant holdExpiresAt
    ) {
        return new SeatStatusUpdateMessage(
                eventId,
                seatIds != null ? seatIds.stream().filter(Objects::nonNull).toList() : List.of(),
                status,
                Instant.now(),
                status == SeatStatus.HELD ? holdExpiresAt : null
        );
    }

    public static SeatStatusUpdateMessage of(
            UUID eventId,
            UUID seatId,
            SeatStatus status
    ) {
        return new SeatStatusUpdateMessage(
                eventId,
                seatId != null ? List.of(seatId) : List.of(),
                status,
                Instant.now(),
                null
        );
    }
}
