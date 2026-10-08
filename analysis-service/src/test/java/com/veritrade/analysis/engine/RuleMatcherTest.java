package com.veritrade.analysis.engine;

import static com.veritrade.analysis.engine.TestRules.DEFAULT_CAP;
import static com.veritrade.analysis.engine.TestRules.DEFAULT_MAX_MATCH;
import static com.veritrade.analysis.engine.TestRules.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleMatcherTest {

    private static final RiskRule LITIGATION = rule("LEGAL-001", RiskCategory.LEGAL, Severity.HIGH, "pending litigation");

    private final RuleMatcher matcher = new RuleMatcher(DEFAULT_CAP, DEFAULT_MAX_MATCH);

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   \t\n\r  ", "No risks were identified in this filing."})
    void findsNothingInEmptyBlankOrUnrelatedText(final String text) {
        assertThat(matcher.findMatches(LITIGATION, text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending litigation", "PENDING LITIGATION", "Pending Litigation", "pEnDiNg LiTiGaTiOn"})
    void ignoresLetterCase(final String text) {
        assertThat(matcher.findMatches(LITIGATION, text)).containsExactly(new Match(0, text.length()));
    }

    @Test
    void ignoresLetterCaseOutsideAscii() {
        final RiskRule rule = rule("REG-001", RiskCategory.REGULATORY, Severity.LOW, "übernahme");

        assertThat(matcher.findMatches(rule, "Die ÜBERNAHME wurde geprüft")).containsExactly(new Match(4, 13));
    }

    @Test
    void findsAMatchAtTheStartAndAtTheEndOfTheText() {
        final String text = "pending litigation and more pending litigation";

        assertThat(matcher.findMatches(LITIGATION, text))
                .containsExactly(new Match(0, 18), new Match(text.length() - 18, text.length()));
    }

    @Test
    void reportsUtf16PositionsInMultibyteText() {
        final String text = "Компания 😀 подтверждает: pending litigation 😀";

        final List<Match> matches = matcher.findMatches(LITIGATION, text);

        assertThat(matches).containsExactly(new Match(text.indexOf("pending"), text.indexOf("pending") + 18));
    }

    @Test
    void treatsRegexSpecialCharactersInTheTextAsPlainText() {
        final String text = "a [bracket] (paren) $dollar ^caret *star+ ?q | pipe \\ backslash pending litigation.";

        assertThat(matcher.findMatches(LITIGATION, text)).hasSize(1);
    }

    @Test
    void supportsEscapedRegexSpecialCharactersInPatterns() {
        final RiskRule rule = rule("FIN-009", RiskCategory.FINANCIAL, Severity.LOW, "\\$\\d+(?:\\.\\d+)? billion \\(unaudited\\)");

        assertThat(matcher.findMatches(rule, "a loss of $1.5 billion (unaudited) was")).containsExactly(new Match(10, 34));
    }

    @Test
    void mergesOverlappingPatternsOfOneRuleKeepingTheEarliestMatch() {
        final RiskRule rule = rule("FIN-001", RiskCategory.FINANCIAL, Severity.CRITICAL,
                "going concern", "doubt about our ability to continue as a going concern");
        final String text = "substantial doubt about our ability to continue as a going concern.";

        assertThat(matcher.findMatches(rule, text)).containsExactly(new Match(12, 66));
    }

    @Test
    void prefersTheLongerMatchWhenTwoPatternsStartAtTheSamePosition() {
        final RiskRule rule = rule("FIN-004", RiskCategory.FINANCIAL, Severity.MEDIUM, "goodwill", "goodwill impairment");

        assertThat(matcher.findMatches(rule, "goodwill impairment")).containsExactly(new Match(0, 19));
    }

    @Test
    void dropsAPartiallyOverlappingMatchOfTheSameRule() {
        final RiskRule rule = rule("LEGAL-001", RiskCategory.LEGAL, Severity.HIGH, "pending litigation", "litigation relating");

        assertThat(matcher.findMatches(rule, "pending litigation relating to patents"))
                .containsExactly(new Match(0, 18));
    }

    @Test
    void keepsAdjacentMatchesThatDoNotOverlap() {
        final RiskRule rule = rule("X-001", RiskCategory.MARKET, Severity.LOW, "ab");

        assertThat(matcher.findMatches(rule, "ababab")).containsExactly(new Match(0, 2), new Match(2, 4), new Match(4, 6));
    }

    @Test
    void returnsMatchesOfSeveralPatternsInTextOrder() {
        final RiskRule rule = rule("OPS-004", RiskCategory.OPERATIONAL, Severity.MEDIUM, "pandemic", "natural disaster");

        assertThat(matcher.findMatches(rule, "a natural disaster or a pandemic"))
                .containsExactly(new Match(2, 18), new Match(24, 32));
    }

    @Test
    void returnsExactlyTheCapWhenTheTextHasExactlyThatManyMatches() {
        final String text = "pending litigation. ".repeat(DEFAULT_CAP);

        assertThat(matcher.findMatches(LITIGATION, text)).hasSize(DEFAULT_CAP);
    }

    @Test
    void capsTheMatchesAtTheFirstOnesWhenThereIsOneMore() {
        final String sentence = "pending litigation. ";
        final String text = sentence.repeat(DEFAULT_CAP + 1);

        final List<Match> matches = matcher.findMatches(LITIGATION, text);

        assertThat(matches).hasSize(DEFAULT_CAP);
        assertThat(matches.getLast().start()).isEqualTo((DEFAULT_CAP - 1) * sentence.length());
    }

    @Test
    void appliesAConfiguredCap() {
        final RuleMatcher capped = new RuleMatcher(3, DEFAULT_MAX_MATCH);

        assertThat(capped.findMatches(LITIGATION, "pending litigation ".repeat(10))).hasSize(3);
    }

    @Test
    void ignoresZeroLengthMatches() {
        final RiskRule lookahead = new RiskRule("X-001", RiskCategory.MARKET, Severity.LOW,
                List.of(Pattern.compile("(?=risk)"), Pattern.compile("risk")));

        assertThat(matcher.findMatches(lookahead, "a risk")).containsExactly(new Match(2, 6));
    }

    @Test
    void cutsAVeryLongMatchToTheMaximumLength() {
        final RiskRule greedy = rule("X-001", RiskCategory.MARKET, Severity.LOW, "risk.*");
        final RuleMatcher shortMatches = new RuleMatcher(DEFAULT_CAP, 10);

        assertThat(shortMatches.findMatches(greedy, "risk factors are described below"))
                .containsExactly(new Match(0, 10));
    }

    @Test
    void neverCutsAMatchInsideASurrogatePair() {
        final RiskRule greedy = rule("X-001", RiskCategory.MARKET, Severity.LOW, "risk.*");
        final RuleMatcher shortMatches = new RuleMatcher(DEFAULT_CAP, 6);
        final String text = "risk 😀 tail";

        final List<Match> matches = shortMatches.findMatches(greedy, text);

        assertThat(matches).containsExactly(new Match(0, 5));
        assertThat(Character.isHighSurrogate(text.charAt(matches.getFirst().end() - 1))).isFalse();
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> new RuleMatcher(0, DEFAULT_MAX_MATCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuleMatcher(DEFAULT_CAP, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
