package com.veritrade.ingestion.service;

import com.veritrade.ingestion.domain.FilingStatus;
import java.util.Objects;
import java.util.UUID;

/** A request, carried by an analysis event, to move a filing to {@code target}. */
public record StatusUpdate(UUID eventId, UUID filingId, FilingStatus target, String failureReason) {

    public StatusUpdate {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(filingId, "filingId");
        Objects.requireNonNull(target, "target");
    }
}
