package com.veritrade.reporting.api.dto;

import com.veritrade.reporting.domain.Report;
import com.veritrade.reporting.domain.ReportStatus;
import com.veritrade.reporting.service.ReportView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The report as published in rest-api.openapi.yaml: summary is null and findings empty when FAILED. */
public record ReportResponse(
        UUID filingId,
        ReportStatus status,
        Instant generatedAt,
        String rulesVersion,
        String failureReason,
        ReportSummaryResponse summary,
        List<FindingResponse> findings) {

    public static ReportResponse from(ReportView view) {
        Report report = view.report();
        return new ReportResponse(
                report.getFilingId(),
                report.getStatus(),
                report.getGeneratedAt(),
                report.getRulesVersion(),
                report.getFailureReason(),
                summaryOf(view),
                view.findings().stream().map(FindingResponse::from).toList());
    }

    private static ReportSummaryResponse summaryOf(ReportView view) {
        Report report = view.report();
        if (report.getStatus() == ReportStatus.FAILED) {
            return null;
        }
        return new ReportSummaryResponse(
                report.getTotalFindings(), report.getOverallRiskLevel(), view.byCategory(), view.bySeverity());
    }
}
