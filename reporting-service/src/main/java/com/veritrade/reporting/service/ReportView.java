package com.veritrade.reporting.service;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.domain.FindingEntity;
import com.veritrade.reporting.domain.Report;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A stored report with its ordered findings and the per-category and per-severity summaries. */
public record ReportView(
        Report report,
        List<FindingEntity> findings,
        Map<RiskCategory, Integer> byCategory,
        Map<Severity, Integer> bySeverity) {

    public ReportView {
        findings = List.copyOf(findings);
        byCategory = Collections.unmodifiableMap(byCategory);
        bySeverity = Collections.unmodifiableMap(bySeverity);
    }

    static ReportView of(Report report) {
        return new ReportView(report, report.orderedFindings(), report.countByCategory(), report.countBySeverity());
    }
}
