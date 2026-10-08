package com.veritrade.reporting.service;

import static com.veritrade.reporting.support.TestEvents.completed;
import static com.veritrade.reporting.support.TestEvents.emoji;
import static com.veritrade.reporting.support.TestEvents.failed;
import static com.veritrade.reporting.support.TestEvents.finding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisSummary;
import com.veritrade.contracts.event.FindingPayload;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.config.ApplicationConfig;
import com.veritrade.reporting.domain.FindingEntity;
import com.veritrade.reporting.domain.Report;
import com.veritrade.reporting.domain.ReportStatus;
import com.veritrade.reporting.repository.ProcessedEventRepository;
import com.veritrade.reporting.repository.ReportRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the service against the real Flyway schema in H2. Test-managed transactions are off, so every
 * call commits or rolls back exactly as it does in production.
 */
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:report-service-test;DB_CLOSE_DELAY=-1")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ReportService.class, IdempotencyGuard.class, ApplicationConfig.class})
class ReportServiceTest {

    private static final int MANY_FINDINGS = 1_500;

    @Autowired
    private ReportService service;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private IdempotencyGuard idempotencyGuard;

    @MockitoSpyBean
    private ReportRepository reports;

    private final UUID filingId = UUID.randomUUID();

    @BeforeEach
    void cleanDatabase() {
        reports.deleteAll();
        processedEvents.deleteAll();
    }

    @Test
    void completedEventCreatesTheReportWithFindingsAndSummaries() {
        AnalysisCompletedPayload payload = completed(filingId, RiskLevel.HIGH, List.of(
                finding(RiskCategory.LEGAL, Severity.HIGH, 41),
                finding(RiskCategory.CYBERSECURITY, Severity.CRITICAL, 211),
                finding(RiskCategory.LEGAL, Severity.LOW, 7)));

        assertThat(service.recordCompleted(UUID.randomUUID(), payload)).isEqualTo(RecordOutcome.CREATED);

        ReportView view = service.findReport(filingId).orElseThrow();
        assertThat(view.report().getStatus()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(view.report().getOverallRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(view.report().getTotalFindings()).isEqualTo(3);
        assertThat(view.report().getRulesVersion()).isEqualTo("1.0");
        assertThat(view.report().getGeneratedAt()).isNotNull();
        assertThat(view.findings()).extracting(FindingEntity::getPosition).containsExactly(211, 41, 7);
        assertThat(view.byCategory()).isEqualTo(Map.of(RiskCategory.LEGAL, 2, RiskCategory.CYBERSECURITY, 1));
        assertThat(view.bySeverity()).isEqualTo(Map.of(Severity.CRITICAL, 1, Severity.HIGH, 1, Severity.LOW, 1));
    }

    @Test
    void duplicateEventIdGivesOneReportAndOneSetOfFindings() {
        UUID eventId = UUID.randomUUID();
        AnalysisCompletedPayload payload = completed(filingId, RiskLevel.HIGH,
                List.of(finding(RiskCategory.LEGAL, Severity.HIGH, 1), finding(RiskCategory.MARKET, Severity.LOW, 2)));

        assertThat(service.recordCompleted(eventId, payload)).isEqualTo(RecordOutcome.CREATED);
        assertThat(service.recordCompleted(eventId, payload)).isEqualTo(RecordOutcome.DUPLICATE);
        assertThat(service.recordCompleted(eventId, payload)).isEqualTo(RecordOutcome.DUPLICATE);

        assertThat(reports.count()).isEqualTo(1);
        assertThat(service.findReport(filingId).orElseThrow().findings()).hasSize(2);
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    void sameFilingWithAnotherEventIdOfTheSameTypeIsIgnoredAsLate() {
        service.recordCompleted(UUID.randomUUID(),
                completed(filingId, RiskLevel.HIGH, List.of(finding(RiskCategory.LEGAL, Severity.HIGH, 1))));
        AnalysisCompletedPayload second = completed(filingId, RiskLevel.CRITICAL, List.of(
                finding(RiskCategory.MARKET, Severity.CRITICAL, 5), finding(RiskCategory.MARKET, Severity.CRITICAL, 6)));

        assertThat(service.recordCompleted(UUID.randomUUID(), second)).isEqualTo(RecordOutcome.IGNORED_LATE);

        ReportView view = service.findReport(filingId).orElseThrow();
        assertThat(view.report().getOverallRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(view.findings()).singleElement().extracting(FindingEntity::getCategory).isEqualTo(RiskCategory.LEGAL);
        assertThat(processedEvents.count()).isEqualTo(2);
    }

    @Test
    void sameFilingWithAnotherFailedEventIdKeepsTheFirstReason() {
        service.recordFailed(UUID.randomUUID(), failed(filingId, "first reason"));

        assertThat(service.recordFailed(UUID.randomUUID(), failed(filingId, "second reason")))
                .isEqualTo(RecordOutcome.IGNORED_LATE);
        assertThat(service.findReport(filingId).orElseThrow().report().getFailureReason()).isEqualTo("first reason");
    }

    @Test
    void completedThenFailedKeepsTheCompletedReport() {
        service.recordCompleted(UUID.randomUUID(),
                completed(filingId, RiskLevel.MEDIUM, List.of(finding(RiskCategory.FINANCIAL, Severity.MEDIUM, 3))));

        assertThat(service.recordFailed(UUID.randomUUID(), failed(filingId, "too late")))
                .isEqualTo(RecordOutcome.IGNORED_LATE);

        Report report = service.findReport(filingId).orElseThrow().report();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(report.getFailureReason()).isNull();
        assertThat(reports.count()).isEqualTo(1);
    }

    @Test
    void failedThenCompletedKeepsTheFailedReport() {
        service.recordFailed(UUID.randomUUID(), failed(filingId, "rule engine error"));

        assertThat(service.recordCompleted(UUID.randomUUID(),
                completed(filingId, RiskLevel.HIGH, List.of(finding(RiskCategory.LEGAL, Severity.HIGH, 1)))))
                .isEqualTo(RecordOutcome.IGNORED_LATE);

        ReportView view = service.findReport(filingId).orElseThrow();
        assertThat(view.report().getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(view.report().getFailureReason()).isEqualTo("rule engine error");
        assertThat(view.findings()).isEmpty();
        assertThat(view.report().getOverallRiskLevel()).isNull();
    }

    @Test
    void outOfOrderAndInterleavedEventsOfSeveralFilingsKeepTheFirstTerminalEventOfEach() {
        UUID other = UUID.randomUUID();
        UUID completedEventId = UUID.randomUUID();
        UUID failedEventId = UUID.randomUUID();
        AnalysisCompletedPayload completedPayload =
                completed(filingId, RiskLevel.LOW, List.of(finding(RiskCategory.MARKET, Severity.LOW, 9)));

        service.recordFailed(failedEventId, failed(other, "other failed"));
        service.recordCompleted(completedEventId, completedPayload);
        service.recordCompleted(UUID.randomUUID(), completed(other, RiskLevel.HIGH, List.of()));
        service.recordFailed(UUID.randomUUID(), failed(filingId, "late failure"));
        service.recordCompleted(completedEventId, completedPayload);
        service.recordFailed(failedEventId, failed(other, "other failed"));

        assertThat(service.findReport(filingId).orElseThrow().report().getStatus()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(service.findReport(other).orElseThrow().report().getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(reports.count()).isEqualTo(2);
        assertThat(service.findReport(filingId).orElseThrow().findings()).hasSize(1);
        assertThat(processedEvents.count()).isEqualTo(4);
    }

    @Test
    void zeroFindingsWithLevelNoneGiveAnEmptySummary() {
        service.recordCompleted(UUID.randomUUID(), completed(filingId, RiskLevel.NONE, List.of()));

        ReportView view = service.findReport(filingId).orElseThrow();
        assertThat(view.report().getOverallRiskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(view.report().getTotalFindings()).isZero();
        assertThat(view.findings()).isEmpty();
        assertThat(view.byCategory()).isEmpty();
        assertThat(view.bySeverity()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(RiskLevel.class)
    void storesEveryRiskLevel(RiskLevel level) {
        service.recordCompleted(UUID.randomUUID(), completed(filingId, level, List.of()));

        assertThat(service.findReport(filingId).orElseThrow().report().getOverallRiskLevel()).isEqualTo(level);
    }

    @Test
    void storesEverySeverityAndCategory() {
        List<FindingPayload> findings = new ArrayList<>();
        for (Severity severity : Severity.values()) {
            for (RiskCategory category : RiskCategory.values()) {
                findings.add(finding(category, severity, findings.size()));
            }
        }
        service.recordCompleted(UUID.randomUUID(), completed(filingId, RiskLevel.CRITICAL, findings));

        ReportView view = service.findReport(filingId).orElseThrow();
        int categories = RiskCategory.values().length;
        int severities = Severity.values().length;
        assertThat(view.bySeverity()).hasSize(severities).allSatisfy((severity, count) -> assertThat(count).isEqualTo(categories));
        assertThat(view.byCategory()).hasSize(categories).allSatisfy((category, count) -> assertThat(count).isEqualTo(severities));
        assertThat(view.findings().getFirst().getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(view.findings().getLast().getSeverity()).isEqualTo(Severity.LOW);
    }

    @Test
    void storesManyFindings() {
        List<FindingPayload> findings = IntStream.range(0, MANY_FINDINGS)
                .mapToObj(i -> finding(RiskCategory.values()[i % RiskCategory.values().length],
                        Severity.values()[i % Severity.values().length], i))
                .toList();

        service.recordCompleted(UUID.randomUUID(), completed(filingId, RiskLevel.CRITICAL, findings));

        ReportView view = service.findReport(filingId).orElseThrow();
        assertThat(view.findings()).hasSize(MANY_FINDINGS);
        assertThat(view.report().getTotalFindings()).isEqualTo(MANY_FINDINGS);
        assertThat(view.byCategory().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(MANY_FINDINGS);
    }

    @Test
    void storesTextExactlyAtTheColumnSizes() {
        FindingPayload longest = new FindingPayload(RiskCategory.LEGAL, Severity.HIGH,
                "R".repeat(32), "m".repeat(500), "e".repeat(1000), Integer.MAX_VALUE);
        service.recordCompleted(UUID.randomUUID(), new AnalysisCompletedPayload(filingId, null, "v".repeat(32),
                new AnalysisSummary(1, RiskLevel.HIGH, Map.of(RiskCategory.LEGAL, 1)), List.of(longest)));

        FindingEntity stored = service.findReport(filingId).orElseThrow().findings().getFirst();
        assertThat(stored.getMatchedText()).hasSize(500);
        assertThat(stored.getExcerpt()).hasSize(1000);
        assertThat(stored.getRuleId()).hasSize(32);
        assertThat(stored.getPosition()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void fitsContractValidSupplementaryTextIntoTheColumnsWithoutSplittingPairs() {
        FindingPayload emojiFinding = new FindingPayload(RiskCategory.LEGAL, Severity.HIGH,
                "LEGAL-001", emoji(500), "x" + emoji(999), 0);
        service.recordCompleted(UUID.randomUUID(), completed(filingId, RiskLevel.HIGH, List.of(emojiFinding)));
        UUID failedFiling = UUID.randomUUID();
        service.recordFailed(UUID.randomUUID(), failed(failedFiling, emoji(1000)));

        FindingEntity stored = service.findReport(filingId).orElseThrow().findings().getFirst();
        assertThat(stored.getMatchedText()).isEqualTo(emoji(250));
        assertThat(stored.getExcerpt()).isEqualTo("x" + emoji(499));
        assertThat(service.findReport(failedFiling).orElseThrow().report().getFailureReason()).isEqualTo(emoji(500));
    }

    @Test
    void failureWhileWritingRollsBackTheProcessedEventToo() {
        UUID eventId = UUID.randomUUID();
        FindingPayload broken = new FindingPayload(null, Severity.HIGH, "LEGAL-001", "text", "excerpt", 1);
        AnalysisCompletedPayload payload = new AnalysisCompletedPayload(filingId, null, "1.0",
                new AnalysisSummary(1, RiskLevel.HIGH, Map.of()), List.of(broken));

        assertThatThrownBy(() -> service.recordCompleted(eventId, payload)).isInstanceOf(RuntimeException.class);

        assertThat(processedEvents.existsById(eventId)).isFalse();
        assertThat(reports.existsById(filingId)).isFalse();
    }

    @Test
    void exceptionFromTheRepositoryRollsBackTheProcessedEventAndTheRetrySucceeds() {
        UUID eventId = UUID.randomUUID();
        AnalysisCompletedPayload payload =
                completed(filingId, RiskLevel.LOW, List.of(finding(RiskCategory.LEGAL, Severity.LOW, 1)));
        doThrow(new IllegalStateException("disk full")).when(reports).save(any(Report.class));

        assertThatThrownBy(() -> service.recordCompleted(eventId, payload)).hasMessage("disk full");
        assertThat(processedEvents.count()).isZero();

        reset(reports);
        assertThat(service.recordCompleted(eventId, payload)).isEqualTo(RecordOutcome.CREATED);
        assertThat(processedEvents.existsById(eventId)).isTrue();
    }

    @Test
    void idempotencyGuardOnlyRunsInsideTheReportTransaction() {
        assertThatThrownBy(() -> idempotencyGuard.markProcessed(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> idempotencyGuard.alreadyProcessed(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void unknownFilingHasNoReport() {
        assertThat(service.findReport(UUID.randomUUID())).isEmpty();
    }
}
