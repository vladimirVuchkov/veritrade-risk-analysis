package com.veritrade.contracts.event;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import java.util.Map;

/** Aggregates of an analysis. Categories without findings are omitted from {@code byCategory}. */
public record AnalysisSummary(
        int totalFindings,
        RiskLevel overallRiskLevel,
        Map<RiskCategory, Integer> byCategory) {

    public AnalysisSummary {
        byCategory = byCategory == null ? Map.of() : Map.copyOf(byCategory);
    }
}
