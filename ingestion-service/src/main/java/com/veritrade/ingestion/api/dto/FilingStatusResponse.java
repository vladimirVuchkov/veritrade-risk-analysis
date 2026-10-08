package com.veritrade.ingestion.api.dto;

import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.FilingView;
import java.time.Instant;
import java.util.UUID;

/** {@code failureReason} is null unless the status is FAILED. */
public record FilingStatusResponse(
        UUID filingId,
        String companyName,
        String title,
        FilingStatus status,
        Instant submittedAt,
        String failureReason) {

    public static FilingStatusResponse from(FilingView view) {
        return new FilingStatusResponse(view.id(), view.companyName(), view.title(), view.status(),
                view.submittedAt(), view.failureReason());
    }
}
