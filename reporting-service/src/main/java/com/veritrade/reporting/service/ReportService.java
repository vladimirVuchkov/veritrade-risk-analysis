package com.veritrade.reporting.service;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.FindingPayload;
import com.veritrade.reporting.config.ReportingLimits;
import com.veritrade.reporting.domain.ColumnText;
import com.veritrade.reporting.domain.FindingEntity;
import com.veritrade.reporting.domain.Report;
import com.veritrade.reporting.domain.ReportStatus;
import com.veritrade.reporting.repository.ReportRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores reports from terminal analysis events and reads them back with their summaries.
 * One report per filing: the first terminal event wins, later ones are acknowledged and ignored.
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportRepository reports;
    private final IdempotencyGuard idempotencyGuard;
    private final ReportingLimits limits;
    private final Clock clock;

    public ReportService(
            ReportRepository reports, IdempotencyGuard idempotencyGuard, ReportingLimits limits, Clock clock) {
        this.reports = reports;
        this.idempotencyGuard = idempotencyGuard;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional
    public RecordOutcome recordCompleted(UUID eventId, AnalysisCompletedPayload payload) {
        return record(eventId, payload.filingId(), ReportStatus.COMPLETED, () -> completedReport(payload));
    }

    @Transactional
    public RecordOutcome recordFailed(UUID eventId, AnalysisFailedPayload payload) {
        return record(eventId, payload.filingId(), ReportStatus.FAILED, () -> Report.failed(
                payload.filingId(),
                ColumnText.fit(payload.reason(), limits.failureReasonMaxLength()),
                clock.instant()));
    }

    @Transactional(readOnly = true)
    public Optional<ReportView> findReport(UUID filingId) {
        return reports.findById(filingId).map(ReportView::of);
    }

    private RecordOutcome record(UUID eventId, UUID filingId, ReportStatus incoming, Supplier<Report> newReport) {
        if (idempotencyGuard.alreadyProcessed(eventId)) {
            log.info("Duplicate event ignored: eventId={}, filingId={}, eventStatus={}", eventId, filingId, incoming);
            return RecordOutcome.DUPLICATE;
        }
        Optional<Report> existing = reports.findById(filingId);
        idempotencyGuard.markProcessed(eventId);
        if (existing.isPresent()) {
            log.warn("Late or contradictory event ignored, the first terminal event wins: "
                            + "eventId={}, filingId={}, reportStatus={}, eventStatus={}",
                    eventId, filingId, existing.get().getStatus(), incoming);
            return RecordOutcome.IGNORED_LATE;
        }
        reports.save(newReport.get());
        log.info("Report stored: eventId={}, filingId={}, status={}", eventId, filingId, incoming);
        return RecordOutcome.CREATED;
    }

    private Report completedReport(AnalysisCompletedPayload payload) {
        Report report = Report.completed(
                payload.filingId(),
                payload.summary().overallRiskLevel(),
                payload.summary().totalFindings(),
                ColumnText.fit(payload.rulesVersion(), limits.rulesVersionMaxLength()),
                clock.instant());
        payload.findings().forEach(finding -> report.addFinding(toEntity(finding)));
        return report;
    }

    private FindingEntity toEntity(FindingPayload finding) {
        return new FindingEntity(
                finding.category(),
                finding.severity(),
                ColumnText.fit(finding.ruleId(), limits.ruleIdMaxLength()),
                ColumnText.fit(finding.matchedText(), limits.matchedTextMaxLength()),
                ColumnText.fit(finding.excerpt(), limits.excerptMaxLength()),
                finding.position());
    }
}
