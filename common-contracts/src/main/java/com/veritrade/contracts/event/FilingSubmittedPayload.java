package com.veritrade.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record FilingSubmittedPayload(
        UUID filingId,
        String companyName,
        String title,
        String content,
        Instant submittedAt) {
}
