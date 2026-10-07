package com.veritrade.contracts.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnalysisCompletedPayload(
        UUID filingId,
        Instant analyzedAt,
        String rulesVersion,
        AnalysisSummary summary,
        List<FindingPayload> findings) {

    public AnalysisCompletedPayload {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
