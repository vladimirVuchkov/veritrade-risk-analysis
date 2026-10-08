package com.veritrade.analysis.engine;

import static com.veritrade.analysis.engine.TestRules.DEFAULT_CAP;
import static com.veritrade.analysis.engine.TestRules.DEFAULT_CONTEXT;
import static com.veritrade.analysis.engine.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.veritrade.analysis.domain.AnalysisResult;
import com.veritrade.analysis.domain.Finding;
import com.veritrade.analysis.support.ContractFixtures;
import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.event.FindingPayload;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RiskAnalyzerTest {

    private static final int TWO_MB = 2 * 1024 * 1024;
    private static final Duration TWO_MB_TIME_LIMIT = Duration.ofSeconds(5);

    private final RiskAnalyzer analyzer = TestRules.bundledAnalyzer();

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n\t  \r\n"})
    void emptyOrWhitespaceTextHasNoFindingsAndRiskNone(String text) {
        AnalysisResult result = analyzer.analyze(text);

        assertThat(result.findings()).isEmpty();
        assertThat(result.totalFindings()).isZero();
        assertThat(result.overallRiskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(result.byCategory()).isEmpty();
        assertThat(result.rulesVersion()).isEqualTo("1.0");
    }

    @Test
    void unrelatedTextHasNoFindings() {
        AnalysisResult result = analyzer.analyze("Revenue grew 12% and we opened three new offices in Austin.");

        assertThat(result.findings()).isEmpty();
        assertThat(result.overallRiskLevel()).isEqualTo(RiskLevel.NONE);
    }

    @ParameterizedTest(name = "{0}: {2}")
    @CsvSource({
        "FINANCIAL, FIN-002, 'Management identified a material weakness in internal control.'",
        "LEGAL, LEGAL-001, 'We are subject to pending litigation in Delaware.'",
        "OPERATIONAL, OPS-001, 'A supply chain disruption could delay shipments.'",
        "CYBERSECURITY, CYBER-001, 'We experienced a cybersecurity incident last year.'",
        "REGULATORY, REG-001, 'We are the subject of a regulatory investigation.'",
        "MARKET, MKT-001, 'We face intense competition in every market.'"
    })
    void findsOneMatchPerCategory(RiskCategory category, String ruleId, String text) {
        AnalysisResult result = analyzer.analyze(text);

        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.category()).isEqualTo(category);
            assertThat(finding.ruleId()).isEqualTo(ruleId);
            assertThat(text.substring(finding.position())).startsWith(finding.matchedText());
            assertThat(finding.excerpt()).isEqualTo(text);
        });
        assertThat(result.byCategory()).containsExactly(Map.entry(category, 1));
    }

    @Test
    void reproducesTheFindingsOfTheContractExample() {
        String content = ContractFixtures.example(EventType.FILING_SUBMITTED).get("payload").get("content").asString();
        AnalysisCompletedPayload expected = ContractFixtures.MAPPER.treeToValue(
                ContractFixtures.example(EventType.ANALYSIS_COMPLETED).get("payload"), AnalysisCompletedPayload.class);

        AnalysisResult result = analyzer.analyze(content);

        assertThat(result.findings()).extracting(RiskAnalyzerTest::toPayload).isEqualTo(expected.findings());
        assertThat(result.overallRiskLevel()).isEqualTo(expected.summary().overallRiskLevel());
        assertThat(result.byCategory()).isEqualTo(expected.summary().byCategory());
        assertThat(result.rulesVersion()).isEqualTo(expected.rulesVersion());
    }

    @Test
    void findsTheExpectedRisksInTheSample10K() {
        AnalysisResult result = analyzer.analyze(ContractFixtures.text("samples/sample-10k-excerpt.txt"));

        assertThat(result.findings()).extracting(Finding::ruleId).containsExactlyInAnyOrder(
                "FIN-001", "FIN-002", "FIN-007", "FIN-003", "FIN-003", "FIN-004",
                "LEGAL-001", "LEGAL-001", "LEGAL-002", "LEGAL-003",
                "OPS-002", "OPS-001", "OPS-003", "OPS-004", "OPS-004",
                "CYBER-001", "CYBER-002", "CYBER-003",
                "REG-001", "REG-003", "REG-004",
                "MKT-001", "MKT-004", "MKT-003", "MKT-005", "MKT-002");
        assertThat(result.byCategory()).containsOnly(
                Map.entry(RiskCategory.FINANCIAL, 6), Map.entry(RiskCategory.LEGAL, 4),
                Map.entry(RiskCategory.OPERATIONAL, 5), Map.entry(RiskCategory.CYBERSECURITY, 3),
                Map.entry(RiskCategory.REGULATORY, 3), Map.entry(RiskCategory.MARKET, 5));
        assertThat(result.overallRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.findings()).filteredOn(f -> f.ruleId().equals("FIN-001")).singleElement()
                .extracting(Finding::matchedText)
                .isEqualTo("substantial doubt about our ability to continue as a going concern");
    }

    @Test
    void countsFindingsPerCategoryAndOmitsCategoriesWithout() {
        AnalysisResult result = analyzer.analyze(
                "pending litigation, an adverse judgment, and a data breach; also pending litigation.");

        assertThat(result.byCategory()).containsOnly(
                Map.entry(RiskCategory.LEGAL, 3), Map.entry(RiskCategory.CYBERSECURITY, 1));
        assertThat(result.totalFindings()).isEqualTo(4);
    }

    @Test
    void keepsOverlapsOfDifferentRulesOrderedByPositionThenRuleId() {
        RuleSet rules = new RuleSet("t", List.of(
                rule("B-002", RiskCategory.CYBERSECURITY, Severity.LOW, "breach"),
                rule("A-001", RiskCategory.CYBERSECURITY, Severity.HIGH, "breach of contract"),
                rule("C-003", RiskCategory.LEGAL, Severity.LOW, "contract")));
        RiskAnalyzer custom = TestRules.analyzer(rules);
        String text = "contract terms; breach of contract";

        AnalysisResult first = custom.analyze(text);

        assertThat(first.findings()).extracting(Finding::ruleId, Finding::position)
                .containsExactly(
                        tuple("C-003", 0),
                        tuple("A-001", 16),
                        tuple("B-002", 16),
                        tuple("C-003", 26));
        assertThat(custom.analyze(text)).isEqualTo(first);
    }

    @Test
    void capsFindingsPerRule() {
        AnalysisResult result = analyzer.analyze("There is pending litigation. ".repeat(DEFAULT_CAP + 1));

        assertThat(result.findings()).hasSize(DEFAULT_CAP).allMatch(f -> f.ruleId().equals("LEGAL-001"));
        assertThat(result.overallRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void handlesMultibyteTextWithCorrectPositionsAndExcerpts() {
        String text = "Отчёт 😀 компании: 我们面临 pending litigation 😀 и риски.";

        AnalysisResult result = analyzer.analyze(text);

        Finding finding = result.findings().getFirst();
        assertThat(finding.position()).isEqualTo(text.indexOf("pending litigation"));
        assertThat(finding.matchedText()).isEqualTo("pending litigation");
        assertThat(finding.excerpt()).isEqualTo(text);
    }

    @Test
    void excerptsAreBoundedAndMatchesAtTheEdgesAreSafe() {
        String filler = "x".repeat(DEFAULT_CONTEXT * 2);
        String text = "pending litigation " + filler + " pending litigation";

        List<Finding> findings = analyzer.analyze(text).findings();

        assertThat(findings).hasSize(2);
        assertThat(findings.getFirst().position()).isZero();
        assertThat(findings.getFirst().excerpt()).hasSize(18 + DEFAULT_CONTEXT).startsWith("pending litigation");
        assertThat(findings.getLast().position()).isEqualTo(text.length() - 18);
        assertThat(findings.getLast().excerpt()).hasSize(DEFAULT_CONTEXT + 18).endsWith("pending litigation");
    }

    @Test
    void analysesTwoMegabytesWithinTheTimeLimit() {
        String paragraph = ContractFixtures.text("samples/sample-10k-excerpt.txt");
        String text = paragraph.repeat(TWO_MB / paragraph.length() + 1).substring(0, TWO_MB);

        AnalysisResult result = assertTimeoutPreemptively(TWO_MB_TIME_LIMIT, () -> analyzer.analyze(text));

        Map<String, Long> perRule = result.findings().stream()
                .collect(Collectors.groupingBy(Finding::ruleId, Collectors.counting()));
        assertThat(perRule.values()).allMatch(count -> count <= DEFAULT_CAP);
        assertThat(perRule).containsEntry("FIN-002", (long) DEFAULT_CAP);
        assertThat(result.overallRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void rejectsNullText() {
        assertThatThrownBy(() -> analyzer.analyze(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void positionsPointAtTheMatchedText() {
        String text = ContractFixtures.text("samples/sample-10k-excerpt.txt");

        assertThat(analyzer.analyze(text).findings()).allSatisfy(finding -> {
            assertThat(text.startsWith(finding.matchedText(), finding.position())).isTrue();
            assertThat(finding.excerpt()).contains(finding.matchedText());
            assertThat(finding.excerpt().length()).isLessThanOrEqualTo(finding.matchedText().length() + 2 * DEFAULT_CONTEXT);
        });
    }

    private static FindingPayload toPayload(Finding finding) {
        return new FindingPayload(finding.category(), finding.severity(), finding.ruleId(),
                finding.matchedText(), finding.excerpt(), finding.position());
    }
}
