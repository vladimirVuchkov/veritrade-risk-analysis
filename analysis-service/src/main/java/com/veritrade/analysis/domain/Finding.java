package com.veritrade.analysis.domain;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import java.util.Objects;

/**
 * One rule match in a filing. {@code position} is the zero-based UTF-16 character offset of
 * {@code matchedText} in the filing content.
 */
public record Finding(
        RiskCategory category,
        Severity severity,
        String ruleId,
        String matchedText,
        String excerpt,
        int position) {

    public Finding {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(matchedText, "matchedText");
        Objects.requireNonNull(excerpt, "excerpt");
        if (position < 0) {
            throw new IllegalArgumentException("position must be >= 0");
        }
    }
}
