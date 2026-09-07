package com.seatflow.ai.orchestration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic mutable clock for expiry tests (avoids sleeps).
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this.now = new AtomicReference<>(initial);
        this.zone = ZoneId.of("UTC");
    }

    public void advance(java.time.Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("MutableClock is fixed to UTC");
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
