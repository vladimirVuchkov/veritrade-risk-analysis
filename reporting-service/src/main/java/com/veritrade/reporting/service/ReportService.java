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
            final ReportRepository reports, final IdempotencyGuard idempotencyGuard, final ReportingLimits limits, final Clock clock) {
        this.reports = reports;
        this.idempotencyGuard = idempotencyGuard;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional
    public RecordOutcome recordCompleted(final UUID eventId, final AnalysisCompletedPayload payload) {
        return record(eventId, payload.filingId(), ReportStatus.COMPLETED, () -> completedReport(payload));
    }

    @Transactional
    public RecordOutcome recordFailed(final UUID eventId, final AnalysisFailedPayload payload) {
        return record(eventId, payload.filingId(), ReportStatus.FAILED, () -> Report.failed(
                payload.filingId(),
                ColumnText.fit(payload.reason(), limits.failureReasonMaxLength()),
                clock.instant()));
    }

    @Transactional(readOnly = true)
    public Optional<ReportView> findReport(final UUID filingId) {
        return reports.findById(filingId).map(ReportView::of);
    }

    private RecordOutcome record(final UUID eventId, final UUID filingId, final ReportStatus incoming, final Supplier<Report> newReport) {
        if (idempotencyGuard.alreadyProcessed(eventId)) {
            log.info("Duplicate event ignored: eventId={}, filingId={}, eventStatus={}", eventId, filingId, incoming);
            return RecordOutcome.DUPLICATE;
        }
        final Optional<Report> existing = reports.findById(filingId);
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

    private Report completedReport(final AnalysisCompletedPayload payload) {
        final Report report = Report.completed(
                payload.filingId(),
                payload.summary().overallRiskLevel(),
                payload.summary().totalFindings(),
                ColumnText.fit(payload.rulesVersion(), limits.rulesVersionMaxLength()),
                clock.instant());
        payload.findings().forEach(finding -> report.addFinding(toEntity(finding)));
        return report;
    }

    private FindingEntity toEntity(final FindingPayload finding) {
        return new FindingEntity(
                finding.category(),
                finding.severity(),
                ColumnText.fit(finding.ruleId(), limits.ruleIdMaxLength()),
                ColumnText.fit(finding.matchedText(), limits.matchedTextMaxLength()),
                ColumnText.fit(finding.excerpt(), limits.excerptMaxLength()),
                finding.position());
    }
}
