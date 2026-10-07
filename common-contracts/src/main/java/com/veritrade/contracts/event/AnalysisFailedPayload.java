package com.veritrade.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record AnalysisFailedPayload(UUID filingId, Instant failedAt, String reason) {
}
