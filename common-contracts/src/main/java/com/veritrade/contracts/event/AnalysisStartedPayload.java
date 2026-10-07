package com.veritrade.contracts.event;

import java.time.Instant;
import java.util.UUID;

public record AnalysisStartedPayload(UUID filingId, Instant startedAt) {
}
