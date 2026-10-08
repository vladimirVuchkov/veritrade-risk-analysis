package com.veritrade.reporting.api.dto;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.domain.FindingEntity;

public record FindingResponse(
        RiskCategory category,
        Severity severity,
        String ruleId,
        String matchedText,
        String excerpt,
        int position) {

    public static FindingResponse from(final FindingEntity finding) {
        return new FindingResponse(
                finding.getCategory(),
                finding.getSeverity(),
                finding.getRuleId(),
                finding.getMatchedText(),
                finding.getExcerpt(),
                finding.getPosition());
    }
}
