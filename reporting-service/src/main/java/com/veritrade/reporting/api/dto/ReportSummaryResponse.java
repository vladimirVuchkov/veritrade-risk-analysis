package com.veritrade.reporting.api.dto;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.util.Map;

/** Categories and severities without findings are omitted from the maps. */
public record ReportSummaryResponse(
        int totalFindings,
        RiskLevel overallRiskLevel,
        Map<RiskCategory, Integer> byCategory,
        Map<Severity, Integer> bySeverity) {
}
