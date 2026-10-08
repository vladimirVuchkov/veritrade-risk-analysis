package com.veritrade.ingestion.domain;

/**
 * Lifecycle of a filing: {@code SUBMITTED -> ANALYZING -> COMPLETED | FAILED}. A filing may skip
 * {@code ANALYZING} when {@code analysis.started} is late or lost. {@code COMPLETED} and
 * {@code FAILED} are final.
 */
public enum FilingStatus {
    SUBMITTED,
    ANALYZING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /** A move to the current status is a duplicate; a move the lifecycle forbids is rejected. */
    public StatusChange transitionTo(final FilingStatus target) {
        if (this == target) {
            return StatusChange.DUPLICATE;
        }
        return canMoveTo(target) ? StatusChange.APPLIED : StatusChange.REJECTED;
    }

    /** Only a failed filing keeps a failure reason. */
    public String failureReasonToKeep(final String reason) {
        return this == FAILED ? reason : null;
    }

    public boolean canMoveTo(final FilingStatus target) {
        return switch (this) {
            case SUBMITTED -> target != SUBMITTED;
            case ANALYZING -> target.isTerminal();
            case COMPLETED, FAILED -> false;
        };
    }
}
