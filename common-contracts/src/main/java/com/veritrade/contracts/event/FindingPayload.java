package com.veritrade.contracts.event;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;

/**
 * One rule match. {@code position} is the zero-based character offset of {@code matchedText}
 * in the filing content; {@code excerpt} is the surrounding context.
 */
public record FindingPayload(
        RiskCategory category,
        Severity severity,
        String ruleId,
        String matchedText,
        String excerpt,
        int position) {
}
