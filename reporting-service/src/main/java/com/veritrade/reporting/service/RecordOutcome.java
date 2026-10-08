package com.veritrade.reporting.service;

/** What happened to a terminal analysis event. Every outcome is acknowledged on the broker. */
public enum RecordOutcome {
    /** The event created the report. */
    CREATED,
    /** The same eventId was already processed (redelivery). */
    DUPLICATE,
    /** Another terminal event already produced the report; the first terminal event wins. */
    IGNORED_LATE
}
