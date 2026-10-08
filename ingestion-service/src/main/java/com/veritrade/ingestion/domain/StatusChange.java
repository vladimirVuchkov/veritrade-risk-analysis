package com.veritrade.ingestion.domain;

/** Outcome of asking a filing to move to a new status. */
public enum StatusChange {
    /** The filing moved to the requested status. */
    APPLIED,
    /** The filing already had the requested status (redelivered or repeated event). */
    DUPLICATE,
    /** The move is not allowed (late or contradictory event); the filing is unchanged. */
    REJECTED
}
