package com.veritrade.ingestion.messaging;

import com.veritrade.ingestion.config.IngestionProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Exponential pause after a failed outbox run, so a broker outage or a stuck row does not reload every
 * pending payload on each scheduler tick. A successful run resets it. Used by the single publisher thread only.
 */
final class RetryBackoff {

    private final Clock clock;
    private final Duration initial;
    private final double multiplier;
    private final Duration max;
    private Duration current = Duration.ZERO;
    private Instant resumeAt = Instant.MIN;

    RetryBackoff(Clock clock, IngestionProperties.Outbox settings) {
        this.clock = clock;
        this.initial = settings.retryBackoff();
        this.multiplier = settings.retryBackoffMultiplier();
        this.max = settings.maxRetryBackoff();
    }

    boolean isPaused() {
        return clock.instant().isBefore(resumeAt);
    }

    /** Returns the pause before the next run. */
    Duration failed() {
        current = current.isZero() ? initial : longer(current);
        resumeAt = clock.instant().plus(current);
        return current;
    }

    void succeeded() {
        current = Duration.ZERO;
        resumeAt = Instant.MIN;
    }

    private Duration longer(Duration pause) {
        Duration next = Duration.ofMillis(Math.round(pause.toMillis() * multiplier));
        return next.compareTo(max) > 0 ? max : next;
    }
}
