package com.veritrade.e2e.support;

import java.time.Duration;

/** Every wait of the suite. Waits poll with Awaitility; nothing sleeps for a fixed time. */
public final class Timeouts {

    /** {@code docker compose up --build --wait}, including the image builds on a cold cache. */
    public static final Duration STACK_START = Duration.ofMinutes(15);
    /** One service (or the broker) from start to healthy. */
    public static final Duration SERVICE_START = Duration.ofMinutes(3);
    /** Any other docker compose command (stop, logs, port, down). */
    public static final Duration COMPOSE_COMMAND = Duration.ofMinutes(2);
    /** Submit (or recovery) to a final status or a report. */
    public static final Duration PROCESSING = Duration.ofSeconds(45);
    /**
     * Submit to a final status or a report for a filing at the 2 MB content limit: the body crosses nginx,
     * Ingestion's outbox, the broker and every rule of Analysis. About 2 s on colima; the rest is margin
     * for a slow CI host.
     */
    public static final Duration LARGE_FILING_PROCESSING = Duration.ofSeconds(60);
    /** Success criterion C2: submit to report. */
    public static final Duration REPORT_DEADLINE = Duration.ofSeconds(10);
    /** How long a filing must stay untouched while its consumer is down. */
    public static final Duration HOLD = Duration.ofSeconds(5);
    /** Publish to a message in a dead-letter queue, or to a log line. */
    public static final Duration MESSAGE_HANDLED = Duration.ofSeconds(20);
    /** The management API refreshes queue statistics every 5 s. */
    public static final Duration QUEUE_STATISTICS = Duration.ofSeconds(30);
    /**
     * The shortest time that the listener retry (initial interval 1 s, multiplier 2, 3 attempts) needs
     * before it gives up. A message dead-lettered faster than this was not retried.
     */
    public static final Duration MIN_RETRY_BACKOFF = Duration.ofSeconds(3);
    /**
     * A stopped upstream to nginx's 503: a stopped container's name no longer resolves, and a cached
     * address of it fails after nginx's 2 s {@code proxy_connect_timeout}. One request, no retry.
     */
    public static final Duration UPSTREAM_UNAVAILABLE = Duration.ofSeconds(5);
    /** nginx keeps a resolved upstream address for 5 s ({@code resolver ... valid=5s}), plus a margin. */
    public static final Duration UPSTREAM_ADDRESS_CACHE = Duration.ofSeconds(7);
    public static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    public static final Duration HTTP_REQUEST = Duration.ofSeconds(15);
    /** The frontend flow (Node) polls with its own short interval; this bounds the whole run. */
    public static final Duration NODE_FLOW = Duration.ofMinutes(2);

    private Timeouts() {
    }
}
