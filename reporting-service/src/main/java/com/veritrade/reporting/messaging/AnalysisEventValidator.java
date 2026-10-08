package com.veritrade.reporting.messaging;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.AnalysisSummary;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.FindingPayload;
import com.veritrade.reporting.config.ReportingLimits;
import org.springframework.stereotype.Component;

/**
 * Checks the parts of the analysis-completed and analysis-failed schemas that Reporting relies on.
 * Text limits are counted in code points, as in the JSON schemas.
 */
@Component
public class AnalysisEventValidator {

    private final ReportingLimits limits;

    public AnalysisEventValidator(ReportingLimits limits) {
        this.limits = limits;
    }

    public void validate(EventEnvelope<?> envelope) {
        if (envelope.eventVersion() > EventEnvelope.CURRENT_VERSION) {
            throw new InvalidEventException("Unsupported eventVersion: " + envelope.eventVersion());
        }
        switch (envelope.payload()) {
            case AnalysisCompletedPayload completed -> validateCompleted(completed);
            case AnalysisFailedPayload failed -> validateFailed(failed);
            default -> throw new InvalidEventException("Unsupported payload: " + envelope.eventType());
        }
    }

    private void validateCompleted(AnalysisCompletedPayload payload) {
        require(payload.filingId() != null, "filingId is required");
        require(payload.analyzedAt() != null, "analyzedAt is required");
        requireText(payload.rulesVersion(), limits.rulesVersionMaxLength(), "rulesVersion");
        validateSummary(payload.summary());
        payload.findings().forEach(this::validateFinding);
    }

    private static void validateSummary(AnalysisSummary summary) {
        require(summary != null, "summary is required");
        require(summary.overallRiskLevel() != null, "summary.overallRiskLevel is required");
        require(summary.totalFindings() >= 0, "summary.totalFindings must not be negative");
    }

    private void validateFinding(FindingPayload finding) {
        require(finding != null, "finding must not be null");
        require(finding.category() != null, "finding.category is required");
        require(finding.severity() != null, "finding.severity is required");
        requireText(finding.ruleId(), limits.ruleIdMaxLength(), "finding.ruleId");
        requireText(finding.matchedText(), limits.matchedTextMaxLength(), "finding.matchedText");
        requireText(finding.excerpt(), limits.excerptMaxLength(), "finding.excerpt");
        require(finding.position() >= 0, "finding.position must not be negative");
    }

    private void validateFailed(AnalysisFailedPayload payload) {
        require(payload.filingId() != null, "filingId is required");
        require(payload.failedAt() != null, "failedAt is required");
        requireText(payload.reason(), limits.failureReasonMaxLength(), "reason");
    }

    private static void requireText(String value, int maxCodePoints, String field) {
        require(value != null && !value.isEmpty(), field + " is required");
        require(value.codePointCount(0, value.length()) <= maxCodePoints,
                field + " is longer than " + maxCodePoints + " characters");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidEventException("Invalid event: " + message);
        }
    }
}
