package com.veritrade.analysis.engine;

import static com.veritrade.analysis.engine.TestRules.DEFAULT_CAP;
import static com.veritrade.analysis.engine.TestRules.DEFAULT_MAX_MATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.veritrade.analysis.domain.AnalysisResult;
import com.veritrade.analysis.domain.Finding;
import com.veritrade.analysis.support.ContractFixtures;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** W3-03: hard-wrapped filings break phrases with line breaks, tabs and runs of spaces. */
class WhitespaceTolerantMatchingTest {

    private static final int WRAP_COLUMNS = 72;
    /** Long enough that a quadratic pattern exceeds the read limit, short enough to stay fast. */
    private static final int QUADRATIC_PROBE_LENGTH = 20_000;

    private final RuleSet rules = TestRules.bundledRules();
    private final RiskAnalyzer analyzer = TestRules.analyzer(rules);

    static Stream<Arguments> separators() {
        return Stream.of(
                Arguments.of("line feed", "\n"),
                Arguments.of("CRLF", "\r\n"),
                Arguments.of("tab", "\t"),
                Arguments.of("two spaces", "  "),
                Arguments.of("many spaces", "        "),
                Arguments.of("wrapped and indented", " \r\n    "),
                Arguments.of("no-break space", " "),
                Arguments.of("narrow no-break space", " "),
                Arguments.of("line separator", " "),
                Arguments.of("form feed", "\f"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("separators")
    void matchesAPhraseBrokenByWhitespace(String name, String separator) {
        String phrase = "substantial doubt about our ability to continue as a going" + separator + "concern";
        String text = "These conditions raise " + phrase + ". Management plans are described in Note 2.";

        Finding finding = singleFinding(text, "FIN-001");

        assertThat(finding.matchedText()).isEqualTo(phrase);
        assertThat(finding.position()).isEqualTo(text.indexOf(phrase));
        assertThat(finding.excerpt()).isEqualTo(text);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("separators")
    void matchesWhenEverySpaceOfAPhraseIsReplaced(String name, String separator) {
        String phrase = String.join(separator, "unauthorized", "access", "to", "customer", "data");

        assertThat(singleFinding(phrase, "CYBER-002").matchedText()).isEqualTo(phrase);
    }

    @Test
    void matchesAPhraseAtTheStartAndAtTheEndOfTheText() {
        String text = "material\r\nweakness was found; we cannot rule out a data\n\tbreach";

        List<Finding> findings = analyzer.analyze(text).findings();

        assertThat(findings).extracting(Finding::ruleId, Finding::position, Finding::matchedText).containsExactly(
                tuple("FIN-002", 0, "material\r\nweakness"),
                tuple("CYBER-002", text.indexOf("data"), "data\n\tbreach"));
        assertThat(findings).allSatisfy(f -> assertThat(f.excerpt()).isEqualTo(text));
    }

    @Test
    void matchesTheFormerCharacterClassAlternativesAcrossLineBreaks() {
        String text = "We rely on sole\nsource suppliers. A denial-of\nservice attack and a cyber\r\nattack hit us.";

        assertThat(analyzer.analyze(text).findings()).extracting(Finding::ruleId)
                .containsExactly("OPS-002", "CYBER-003", "CYBER-001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"goingconcern", "going-concern", "going_concern", "going.concern"})
    void doesNotTreatOtherCharactersAsWhitespace(String text) {
        assertThat(analyzer.analyze(text).findings()).isEmpty();
    }

    @Test
    void theSampleWrappedAt72ColumnsGivesTheSameFindings() {
        String sample = ContractFixtures.text("samples/sample-10k-excerpt.txt");
        String wrapped = wrap(sample, WRAP_COLUMNS);
        assertThat(wrapped.lines()).allMatch(line -> line.length() <= WRAP_COLUMNS);

        assertSameRulesAndCategories(sample, wrapped);
        assertSameRulesAndCategories(sample, wrapped.replace("\n", "\r\n"));
        assertThat(analyzer.analyze(wrapped).findings()).extracting(Finding::matchedText)
                .anySatisfy(matched -> assertThat(matched).contains("\n"));
    }

    @Test
    void wrappedFindingsPointAtTheOriginalText() {
        String wrapped = wrap(ContractFixtures.text("samples/sample-10k-excerpt.txt"), WRAP_COLUMNS).replace("\n", "\r\n");

        assertThat(analyzer.analyze(wrapped).findings()).isNotEmpty().allSatisfy(finding -> {
            assertThat(wrapped.startsWith(finding.matchedText(), finding.position())).isTrue();
            assertThat(finding.excerpt()).contains(finding.matchedText());
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adversarialInputs")
    void whitespaceHeavyInputCannotBacktrackCatastrophically(String name, String text) {
        RuleMatcher matcher = new RuleMatcher(DEFAULT_CAP, DEFAULT_MAX_MATCH);

        double readsPerChar = assertTimeout(TestRules.GENEROUS_TIME_LIMIT,
                () -> CountingText.readsPerChar(rules, matcher, text));

        assertThat(readsPerChar).isLessThan(CountingText.MAX_LINEAR_READS_PER_CHAR);
    }

    @Test
    void theReadCountExposesSuperLinearMatching() {
        RuleSet quadratic = new RuleSet("t", List.of(TestRules.rule("X-001",
                RiskCategory.MARKET, Severity.LOW,
                WhitespaceTolerance.WHITESPACE_RUN + "x")));
        RuleMatcher matcher = new RuleMatcher(DEFAULT_CAP, DEFAULT_MAX_MATCH);

        assertThat(CountingText.readsPerChar(quadratic, matcher, " ".repeat(QUADRATIC_PROBE_LENGTH)))
                .isGreaterThan(CountingText.MAX_LINEAR_READS_PER_CHAR);
    }

    static Stream<Arguments> adversarialInputs() {
        return Stream.of(
                Arguments.of("one phrase start, then only whitespace", twoMegabytes("loss of", " \t\r\n ")),
                Arguments.of("unfinished phrases with long whitespace runs",
                        twoMegabytes("continue as a going" + " ".repeat(4000) + "loss of a" + "\t".repeat(4000), "")),
                Arguments.of("every word followed by mixed whitespace",
                        twoMegabytes("", "loss \t of \r\n one   or \n more  significant\t\t")),
                Arguments.of("only whitespace", twoMegabytes("", " \n\t\r ")));
    }

    private void assertSameRulesAndCategories(String original, String variant) {
        AnalysisResult expected = analyzer.analyze(original);
        AnalysisResult actual = analyzer.analyze(variant);

        assertThat(actual.findings()).extracting(Finding::ruleId)
                .containsExactlyInAnyOrderElementsOf(expected.findings().stream().map(Finding::ruleId).toList());
        assertThat(actual.byCategory()).isEqualTo(expected.byCategory());
        assertThat(actual.overallRiskLevel()).isEqualTo(expected.overallRiskLevel());
    }

    private Finding singleFinding(String text, String ruleId) {
        List<Finding> findings = analyzer.analyze(text).findings();
        assertThat(findings).extracting(Finding::ruleId).contains(ruleId);
        return findings.stream().filter(f -> f.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }

    private static String twoMegabytes(String head, String repeated) {
        StringBuilder text = new StringBuilder(TestRules.TWO_MB).append(head);
        String unit = repeated.isEmpty() ? head : repeated;
        while (text.length() < TestRules.TWO_MB) {
            text.append(unit);
        }
        return text.substring(0, TestRules.TWO_MB);
    }

    /** Greedy word wrap of every line, like a plain-text EDGAR filing. */
    private static String wrap(String text, int columns) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            int lineLength = 0;
            for (String word : line.split(" ")) {
                if (lineLength > 0 && lineLength + 1 + word.length() > columns) {
                    out.append('\n');
                    lineLength = 0;
                } else if (lineLength > 0) {
                    out.append(' ');
                    lineLength++;
                }
                out.append(word);
                lineLength += word.length();
            }
            out.append('\n');
        }
        return out.substring(0, out.length() - 1);
    }
}
