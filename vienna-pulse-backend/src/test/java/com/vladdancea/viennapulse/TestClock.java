package com.vladdancea.viennapulse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/// A clock that stands still at the instant a test sets, so the application's idea of now is known.
public class TestClock extends Clock {
    // Requests read it on the server's threads.
    private volatile Instant instant = Instant.now();

    /// Moves the clock to `instant`, written as in [Instant#parse], such as `2026-10-19T22:30:00Z`.
    public void set(String instant) {
        this.instant = Instant.parse(instant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
