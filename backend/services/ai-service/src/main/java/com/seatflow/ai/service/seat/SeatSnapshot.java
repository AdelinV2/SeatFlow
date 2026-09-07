package com.seatflow.ai.service.seat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit upstream snapshot that deterministic ranking operates over (TASK-P15-003 section 10).
 *
 * <p>The snapshot is assembled once per tool call from live service responses and never persisted:
 * ranking is a pure function of this value, so repeated calls over an identical snapshot return
 * identical ordering. It is a snapshot, not a hold — confirmation must revalidate before any
 * reservation is created.
 */
public record SeatSnapshot(
        UUID eventSessionId,
        Instant snapshotAt,
        List<PricedSeat> seats,
        Optional<SeatGeometry.Point> stageCenter,
        Optional<SeatGeometry.Point> venueCenter,
        boolean geometryReliable
) {
    public SeatSnapshot {
        seats = seats == null ? List.of() : List.copyOf(seats);
    }
}
