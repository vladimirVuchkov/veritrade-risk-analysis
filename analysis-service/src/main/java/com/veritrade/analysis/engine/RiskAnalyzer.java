package com.veritrade.analysis.engine;

import com.veritrade.analysis.domain.AnalysisResult;
import com.veritrade.analysis.domain.Finding;
import com.veritrade.contracts.model.RiskCategory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs every rule over a filing text and summarizes the findings. Pure logic: no Spring, no broker.
 * Findings are ordered by position, then by rule id, so the same text always gives the same result.
 */
public class RiskAnalyzer {

    private static final Comparator<Finding> BY_POSITION_THEN_RULE =
            Comparator.comparingInt(Finding::position).thenComparing(Finding::ruleId);

    private final RuleSet ruleSet;
    private final RuleMatcher matcher;
    private final ExcerptExtractor excerpts;
    private final RiskScorer scorer;

    public RiskAnalyzer(final RuleSet ruleSet, final RuleMatcher matcher, final ExcerptExtractor excerpts, final RiskScorer scorer) {
        this.ruleSet = Objects.requireNonNull(ruleSet, "ruleSet");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.excerpts = Objects.requireNonNull(excerpts, "excerpts");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    public AnalysisResult analyze(final String text) {
        Objects.requireNonNull(text, "text");
        final List<Finding> findings = new ArrayList<>();
        for (final RiskRule rule : ruleSet.rules()) {
            for (final Match match : matcher.findMatches(rule, text)) {
                findings.add(toFinding(rule, match, text));
            }
        }
        findings.sort(BY_POSITION_THEN_RULE);
        return new AnalysisResult(ruleSet.rulesVersion(), findings, scorer.score(findings), countByCategory(findings));
    }

    private Finding toFinding(final RiskRule rule, final Match match, final String text) {
        return new Finding(
                rule.category(),
                rule.severity(),
                rule.id(),
                text.substring(match.start(), match.end()),
                excerpts.extract(text, match),
                match.start());
    }

    private static Map<RiskCategory, Integer> countByCategory(final List<Finding> findings) {
        final Map<RiskCategory, Integer> counts = new EnumMap<>(RiskCategory.class);
        for (final Finding finding : findings) {
            counts.merge(finding.category(), 1, Integer::sum);
        }
        return counts;
    }
}
