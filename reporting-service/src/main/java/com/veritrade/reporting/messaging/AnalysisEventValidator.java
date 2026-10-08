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
 * Text limits are counted in UTF-16 code units, as the contract specifies (the unit of the columns).
 */
@Component
public class AnalysisEventValidator {

    private final ReportingLimits limits;

    public AnalysisEventValidator(final ReportingLimits limits) {
        this.limits = limits;
    }

    public void validate(final EventEnvelope<?> envelope) {
        if (envelope.eventVersion() > EventEnvelope.CURRENT_VERSION) {
            throw new InvalidEventException("Unsupported eventVersion: " + envelope.eventVersion());
        }
        switch (envelope.payload()) {
            case AnalysisCompletedPayload completed -> validateCompleted(completed);
            case AnalysisFailedPayload failed -> validateFailed(failed);
            default -> throw new InvalidEventException("Unsupported payload: " + envelope.eventType());
        }
    }

    private void validateCompleted(final AnalysisCompletedPayload payload) {
        require(payload.filingId() != null, "filingId is required");
        require(payload.analyzedAt() != null, "analyzedAt is required");
        requireText(payload.rulesVersion(), limits.rulesVersionMaxLength(), "rulesVersion");
        validateSummary(payload.summary());
        payload.findings().forEach(this::validateFinding);
    }

    private static void validateSummary(final AnalysisSummary summary) {
        require(summary != null, "summary is required");
        require(summary.overallRiskLevel() != null, "summary.overallRiskLevel is required");
        require(summary.totalFindings() >= 0, "summary.totalFindings must not be negative");
    }

    private void validateFinding(final FindingPayload finding) {
        require(finding != null, "finding must not be null");
        require(finding.category() != null, "finding.category is required");
        require(finding.severity() != null, "finding.severity is required");
        requireText(finding.ruleId(), limits.ruleIdMaxLength(), "finding.ruleId");
        requireText(finding.matchedText(), limits.matchedTextMaxLength(), "finding.matchedText");
        requireText(finding.excerpt(), limits.excerptMaxLength(), "finding.excerpt");
        require(finding.position() >= 0, "finding.position must not be negative");
    }

    private void validateFailed(final AnalysisFailedPayload payload) {
        require(payload.filingId() != null, "filingId is required");
        require(payload.failedAt() != null, "failedAt is required");
        requireText(payload.reason(), limits.failureReasonMaxLength(), "reason");
    }

    private static void requireText(final String value, final int maxUtf16Units, final String field) {
        require(value != null && !value.isEmpty(), field + " is required");
        require(value.length() <= maxUtf16Units, field + " is longer than " + maxUtf16Units + " UTF-16 units");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new InvalidEventException("Invalid event: " + message);
        }
    }
}
