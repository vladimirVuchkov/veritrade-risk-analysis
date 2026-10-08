package com.veritrade.analysis.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the patterns of one rule to a text. The matches of all patterns of a rule are merged;
 * where two of them overlap, the one that starts first (and, at the same start, the longer one) wins,
 * so one phrase is never reported twice by the same rule. The result is capped per rule.
 * Matches of different rules may overlap: they are different findings.
 */
public final class RuleMatcher {

    static final int MIN_MATCH_LENGTH = 2;

    private static final Comparator<Match> EARLIEST_THEN_LONGEST = Comparator.comparingInt(Match::start)
            .thenComparing(Comparator.comparingInt(Match::end).reversed());

    private final int maxMatchesPerRule;
    private final int maxMatchLength;

    public RuleMatcher(int maxMatchesPerRule, int maxMatchLength) {
        if (maxMatchesPerRule < 1) {
            throw new IllegalArgumentException("maxMatchesPerRule must be >= 1, was " + maxMatchesPerRule);
        }
        if (maxMatchLength < MIN_MATCH_LENGTH) {
            throw new IllegalArgumentException(
                    "maxMatchLength must be >= " + MIN_MATCH_LENGTH + ", was " + maxMatchLength);
        }
        this.maxMatchesPerRule = maxMatchesPerRule;
        this.maxMatchLength = maxMatchLength;
    }

    /** Non-overlapping matches of the rule, earliest first, at most {@code maxMatchesPerRule}. */
    public List<Match> findMatches(RiskRule rule, CharSequence text) {
        List<Match> candidates = new ArrayList<>();
        for (Pattern pattern : rule.patterns()) {
            collect(pattern, text, candidates);
        }
        candidates.sort(EARLIEST_THEN_LONGEST);
        return firstNonOverlapping(candidates);
    }

    private void collect(Pattern pattern, CharSequence text, List<Match> into) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                into.add(new Match(matcher.start(), clippedEnd(text, matcher.start(), matcher.end())));
            }
        }
    }

    private int clippedEnd(CharSequence text, int start, int end) {
        if (end - start <= maxMatchLength) {
            return end;
        }
        return TextBounds.safeStart(text, start + maxMatchLength);
    }

    private List<Match> firstNonOverlapping(List<Match> sorted) {
        List<Match> accepted = new ArrayList<>();
        for (Match candidate : sorted) {
            if (accepted.size() == maxMatchesPerRule) {
                break;
            }
            if (accepted.isEmpty() || !accepted.getLast().overlaps(candidate)) {
                accepted.add(candidate);
            }
        }
        return accepted;
    }
}
