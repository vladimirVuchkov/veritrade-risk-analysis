package com.veritrade.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.analysis.domain.Finding;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class RiskScorerTest {

    private static final int THRESHOLD = 5;

    private final RiskScorer scorer = new RiskScorer(THRESHOLD);

    @Test
    void noFindingsMeansNone() {
        assertThat(scorer.score(List.of())).isEqualTo(RiskLevel.NONE);
    }

    @ParameterizedTest
    @EnumSource(Severity.class)
    void aSingleFindingGivesItsOwnSeverity(Severity severity) {
        assertThat(scorer.score(findings(1, severity))).isEqualTo(RiskLevel.valueOf(severity.name()));
    }

    @Test
    void takesTheHighestSeverity() {
        List<Finding> mixed = List.of(finding(Severity.LOW), finding(Severity.HIGH), finding(Severity.MEDIUM));

        assertThat(scorer.score(mixed)).isEqualTo(RiskLevel.HIGH);
    }

    @ParameterizedTest(name = "{0} with {1} findings -> {2}")
    @MethodSource("thresholdBoundary")
    void raisesOneLevelFromTheThresholdOn(Severity severity, int count, RiskLevel expected) {
        assertThat(scorer.score(findings(count, severity))).isEqualTo(expected);
    }

    static Stream<Arguments> thresholdBoundary() {
        return Stream.of(
                Arguments.of(Severity.LOW, THRESHOLD - 1, RiskLevel.LOW),
                Arguments.of(Severity.LOW, THRESHOLD, RiskLevel.MEDIUM),
                Arguments.of(Severity.LOW, THRESHOLD + 1, RiskLevel.MEDIUM),
                Arguments.of(Severity.MEDIUM, THRESHOLD - 1, RiskLevel.MEDIUM),
                Arguments.of(Severity.MEDIUM, THRESHOLD, RiskLevel.HIGH),
                Arguments.of(Severity.MEDIUM, THRESHOLD + 1, RiskLevel.HIGH),
                Arguments.of(Severity.HIGH, THRESHOLD - 1, RiskLevel.HIGH),
                Arguments.of(Severity.HIGH, THRESHOLD, RiskLevel.CRITICAL),
                Arguments.of(Severity.HIGH, THRESHOLD + 1, RiskLevel.CRITICAL),
                Arguments.of(Severity.CRITICAL, THRESHOLD - 1, RiskLevel.CRITICAL),
                Arguments.of(Severity.CRITICAL, THRESHOLD, RiskLevel.CRITICAL),
                Arguments.of(Severity.CRITICAL, THRESHOLD + 1, RiskLevel.CRITICAL));
    }

    @Test
    void raisesOnlyOneLevelEvenForVeryManyFindings() {
        assertThat(scorer.score(findings(THRESHOLD * 10, Severity.LOW))).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void countsFindingsOfEverySeverityTowardsTheThreshold() {
        List<Finding> mixed = new ArrayList<>(findings(THRESHOLD - 1, Severity.LOW));
        mixed.add(finding(Severity.MEDIUM));

        assertThat(scorer.score(mixed)).isEqualTo(RiskLevel.HIGH);
    }

    @ParameterizedTest
    @CsvSource({"LOW, MEDIUM", "MEDIUM, HIGH", "HIGH, CRITICAL", "CRITICAL, CRITICAL"})
    void aThresholdOfOneRaisesEveryNonEmptyResult(Severity severity, RiskLevel expected) {
        assertThat(new RiskScorer(1).score(findings(1, severity))).isEqualTo(expected);
    }

    @Test
    void rejectsAThresholdBelowOne() {
        assertThatThrownBy(() -> new RiskScorer(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<Finding> findings(int count, Severity severity) {
        return IntStream.range(0, count).mapToObj(i -> finding(severity)).toList();
    }

    private static Finding finding(Severity severity) {
        return new Finding(RiskCategory.LEGAL, severity, "LEGAL-001", "text", "the text", 0);
    }
}
