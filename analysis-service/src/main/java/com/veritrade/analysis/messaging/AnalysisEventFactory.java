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

    private final Clock clock;
    private final int maxReasonLength;

    public AnalysisEventFactory(final Clock clock, final MessagingProperties properties) {
        this.clock = clock;
        this.maxReasonLength = properties.maxReasonLength();
    }

    public EventEnvelope<AnalysisStartedPayload> started(final UUID filingId, final String correlationId) {
        final Instant now = clock.instant();
        return envelope(EventType.ANALYSIS_STARTED, filingId, correlationId, now,
                new AnalysisStartedPayload(filingId, now));
    }

    public EventEnvelope<AnalysisCompletedPayload> completed(final UUID filingId, final String correlationId, final AnalysisResult result) {
        final Instant now = clock.instant();
        final AnalysisSummary summary = new AnalysisSummary(
                result.totalFindings(), result.overallRiskLevel(), result.byCategory());
        final AnalysisCompletedPayload payload = new AnalysisCompletedPayload(
                filingId, now, result.rulesVersion(), summary,
                result.findings().stream().map(AnalysisEventFactory::toPayload).toList());
        return envelope(EventType.ANALYSIS_COMPLETED, filingId, correlationId, now, payload);
    }

    public EventEnvelope<AnalysisFailedPayload> failed(final UUID filingId, final String correlationId, final String reason) {
        final Instant now = clock.instant();
        return envelope(EventType.ANALYSIS_FAILED, filingId, correlationId, now,
                new AnalysisFailedPayload(filingId, now, limit(reason)));
    }

    private static <T> EventEnvelope<T> envelope(
            final EventType type, final UUID filingId, final String correlationId, final Instant now, final T payload) {
        return EventEnvelope.of(EventIds.forFiling(filingId, type), type, now, correlationId, payload);
    }

    private static FindingPayload toPayload(final Finding finding) {
        return new FindingPayload(finding.category(), finding.severity(), finding.ruleId(),
                finding.matchedText(), finding.excerpt(), finding.position());
    }

    /** Cuts the reason to the limit in UTF-16 code units; a surrogate pair at the cut is dropped whole. */
    private String limit(final String reason) {
        if (reason.length() <= maxReasonLength) {
            return reason;
        }
        int end = maxReasonLength;
        if (Character.isHighSurrogate(reason.charAt(end - 1)) && Character.isLowSurrogate(reason.charAt(end))) {
            end--;
        }
        return reason.substring(0, end);
    }
}
