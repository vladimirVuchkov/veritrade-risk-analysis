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

    public boolean canMoveTo(FilingStatus target) {
        return switch (this) {
            case SUBMITTED -> target != SUBMITTED;
            case ANALYZING -> target.isTerminal();
            case COMPLETED, FAILED -> false;
        };
    }
}
