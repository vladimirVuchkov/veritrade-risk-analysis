package com.veritrade.analysis.messaging;

import com.veritrade.analysis.domain.AnalysisResult;
import com.veritrade.analysis.domain.Finding;
import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.AnalysisStartedPayload;
import com.veritrade.contracts.event.AnalysisSummary;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.event.FindingPayload;
import com.veritrade.contracts.messaging.EventIds;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the outgoing analysis events. Event ids are deterministic per (filing, event type), so a
 * redelivered filing produces events with the same ids and consumers drop the duplicates.
 */
@Component
public class AnalysisEventFactory {

    /** Maximum length of {@code reason} in the analysis-failed schema. */
    static final int MAX_REASON_LENGTH = 1000;

    private final Clock clock;

    public AnalysisEventFactory(Clock clock) {
        this.clock = clock;
    }

    public EventEnvelope<AnalysisStartedPayload> started(UUID filingId, String correlationId) {
        Instant now = clock.instant();
        return envelope(EventType.ANALYSIS_STARTED, filingId, correlationId, now,
                new AnalysisStartedPayload(filingId, now));
    }

    public EventEnvelope<AnalysisCompletedPayload> completed(UUID filingId, String correlationId, AnalysisResult result) {
        Instant now = clock.instant();
        AnalysisSummary summary = new AnalysisSummary(
                result.totalFindings(), result.overallRiskLevel(), result.byCategory());
        AnalysisCompletedPayload payload = new AnalysisCompletedPayload(
                filingId, now, result.rulesVersion(), summary,
                result.findings().stream().map(AnalysisEventFactory::toPayload).toList());
        return envelope(EventType.ANALYSIS_COMPLETED, filingId, correlationId, now, payload);
    }

    public EventEnvelope<AnalysisFailedPayload> failed(UUID filingId, String correlationId, String reason) {
        Instant now = clock.instant();
        return envelope(EventType.ANALYSIS_FAILED, filingId, correlationId, now,
                new AnalysisFailedPayload(filingId, now, limit(reason)));
    }

    private static <T> EventEnvelope<T> envelope(
            EventType type, UUID filingId, String correlationId, Instant now, T payload) {
        return EventEnvelope.of(EventIds.forFiling(filingId, type), type, now, correlationId, payload);
    }

    private static FindingPayload toPayload(Finding finding) {
        return new FindingPayload(finding.category(), finding.severity(), finding.ruleId(),
                finding.matchedText(), finding.excerpt(), finding.position());
    }

    private static String limit(String reason) {
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
