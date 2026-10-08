package com.veritrade.ingestion.domain;

import java.time.Instant;
import java.util.UUID;

/** Read model of a filing without its content. */
public record FilingView(
        UUID id,
        String companyName,
        String title,
        FilingStatus status,
        Instant submittedAt,
        String failureReason) {

    public static FilingView of(Filing filing) {
        return new FilingView(filing.id(), filing.companyName(), filing.title(), filing.status(),
                filing.submittedAt(), filing.failureReason());
    }
}
