package com.veritrade.ingestion.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that a test moves forward by hand. */
public final class MutableClock extends Clock {

    private Instant now;

    public MutableClock(final Instant start) {
        this.now = start;
    }

    public void advance(final Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        throw new UnsupportedOperationException("A test clock has a fixed zone");
    }
}
