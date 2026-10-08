package com.veritrade.reporting.domain;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The risk report of one filing. It is written once, by the first terminal analysis event. */
@Entity
@Table(name = "reports")
public class Report {

    private static final Comparator<FindingEntity> HIGHEST_SEVERITY_FIRST = Comparator
            .comparing(FindingEntity::getSeverity, Comparator.reverseOrder())
            .thenComparingInt(FindingEntity::getPosition);

    @Id
    @Column(name = "filing_id")
    private UUID filingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReportStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_risk_level")
    private RiskLevel overallRiskLevel;

    @Column(name = "total_findings", nullable = false)
    private int totalFindings;

    @Column(name = "rules_version")
    private String rulesVersion;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FindingEntity> findings = new ArrayList<>();

    protected Report() {
    }

    private Report(final UUID filingId, final ReportStatus status, final Instant generatedAt) {
        this.filingId = filingId;
        this.status = status;
        this.generatedAt = generatedAt;
    }

    public static Report completed(
            final UUID filingId, final RiskLevel overallRiskLevel, final int totalFindings, final String rulesVersion, final Instant generatedAt) {
        final Report report = new Report(filingId, ReportStatus.COMPLETED, generatedAt);
        report.overallRiskLevel = overallRiskLevel;
        report.totalFindings = totalFindings;
        report.rulesVersion = rulesVersion;
        return report;
    }

    public static Report failed(final UUID filingId, final String failureReason, final Instant generatedAt) {
        final Report report = new Report(filingId, ReportStatus.FAILED, generatedAt);
        report.failureReason = failureReason;
        return report;
    }

    public void addFinding(final FindingEntity finding) {
        finding.attachTo(this);
        findings.add(finding);
    }

    /** Findings ordered by severity (highest first), then by position. */
    public List<FindingEntity> orderedFindings() {
        return findings.stream().sorted(HIGHEST_SEVERITY_FIRST).toList();
    }

    /** Number of findings per category; categories without findings are absent. */
    public Map<RiskCategory, Integer> countByCategory() {
        final Map<RiskCategory, Integer> counts = new EnumMap<>(RiskCategory.class);
        findings.forEach(finding -> counts.merge(finding.getCategory(), 1, Integer::sum));
        return counts;
    }

    /** Number of findings per severity; severities without findings are absent. */
    public Map<Severity, Integer> countBySeverity() {
        final Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        findings.forEach(finding -> counts.merge(finding.getSeverity(), 1, Integer::sum));
        return counts;
    }

    public UUID getFilingId() {
        return filingId;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public RiskLevel getOverallRiskLevel() {
        return overallRiskLevel;
    }

    public int getTotalFindings() {
        return totalFindings;
    }

    public String getRulesVersion() {
        return rulesVersion;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
