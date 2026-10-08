package com.veritrade.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    void ordersFindingsBySeverityHighestFirstThenByPosition() {
        final Report report = Report.completed(UUID.randomUUID(), RiskLevel.CRITICAL, 4, "1.0", NOW);
        report.addFinding(finding(RiskCategory.LEGAL, Severity.LOW, 5));
        report.addFinding(finding(RiskCategory.LEGAL, Severity.CRITICAL, 90));
        report.addFinding(finding(RiskCategory.MARKET, Severity.HIGH, 30));
        report.addFinding(finding(RiskCategory.MARKET, Severity.CRITICAL, 10));

        assertThat(report.orderedFindings())
                .extracting(FindingEntity::getSeverity, FindingEntity::getPosition)
                .containsExactly(
                        tuple(Severity.CRITICAL, 10),
                        tuple(Severity.CRITICAL, 90),
                        tuple(Severity.HIGH, 30),
                        tuple(Severity.LOW, 5));
    }

    @Test
    void countsPerCategoryAndSeverityOmittingEmptyOnes() {
        final Report report = Report.completed(UUID.randomUUID(), RiskLevel.HIGH, 3, "1.0", NOW);
        report.addFinding(finding(RiskCategory.LEGAL, Severity.HIGH, 1));
        report.addFinding(finding(RiskCategory.LEGAL, Severity.LOW, 2));
        report.addFinding(finding(RiskCategory.FINANCIAL, Severity.HIGH, 3));

        assertThat(report.countByCategory())
                .isEqualTo(Map.of(RiskCategory.LEGAL, 2, RiskCategory.FINANCIAL, 1));
        assertThat(report.countBySeverity()).isEqualTo(Map.of(Severity.HIGH, 2, Severity.LOW, 1));
    }

    @Test
    void reportWithoutFindingsHasEmptySummaries() {
        final Report report = Report.completed(UUID.randomUUID(), RiskLevel.NONE, 0, "1.0", NOW);

        assertThat(report.orderedFindings()).isEmpty();
        assertThat(report.countByCategory()).isEmpty();
        assertThat(report.countBySeverity()).isEmpty();
    }

    @Test
    void failedReportKeepsTheReasonAndHasNoRiskData() {
        final UUID filingId = UUID.randomUUID();

        final Report report = Report.failed(filingId, "rule engine error", NOW);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(report.getFilingId()).isEqualTo(filingId);
        assertThat(report.getFailureReason()).isEqualTo("rule engine error");
        assertThat(report.getOverallRiskLevel()).isNull();
        assertThat(report.getRulesVersion()).isNull();
        assertThat(report.getTotalFindings()).isZero();
        assertThat(report.getGeneratedAt()).isEqualTo(NOW);
    }

    private static FindingEntity finding(final RiskCategory category, final Severity severity, final int position) {
        return new FindingEntity(category, severity, "RULE-001", "text", "context text", position);
    }
}
