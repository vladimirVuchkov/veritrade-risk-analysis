package com.veritrade.analysis.domain;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Outcome of analysing one filing. Categories without findings are absent from {@code byCategory}. */
public record AnalysisResult(
        String rulesVersion,
        List<Finding> findings,
        RiskLevel overallRiskLevel,
        Map<RiskCategory, Integer> byCategory) {

    public AnalysisResult {
        Objects.requireNonNull(rulesVersion, "rulesVersion");
        Objects.requireNonNull(overallRiskLevel, "overallRiskLevel");
        findings = List.copyOf(findings);
        byCategory = Map.copyOf(byCategory);
    }

    public int totalFindings() {
        return findings.size();
    }
}
