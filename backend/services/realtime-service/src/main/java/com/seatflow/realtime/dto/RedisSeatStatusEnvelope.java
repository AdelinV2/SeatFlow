package com.seatflow.realtime.dto;

import java.time.Instant;
import java.util.UUID;

/** Best-effort Redis Pub/Sub payload; sourceEventId is stable across Kafka retries. */
public record RedisSeatStatusEnvelope(
        String sourceEventId,
        UUID messageId,
        String originInstanceId,
        Instant publishedAt,
        SeatStatusUpdateMessage payload
) {
}
