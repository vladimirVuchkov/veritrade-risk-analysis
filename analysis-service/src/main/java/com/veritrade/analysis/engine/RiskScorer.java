package com.veritrade.analysis.engine;

import com.veritrade.analysis.domain.Finding;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.util.Collection;
import java.util.Comparator;

/**
 * Overall risk level of a filing:
 * <ul>
 *   <li>no findings: {@link RiskLevel#NONE};</li>
 *   <li>otherwise the highest severity found;</li>
 *   <li>raised by one level when the number of findings is at least {@code escalationThreshold}
 *       ({@code CRITICAL} stays {@code CRITICAL}).</li>
 * </ul>
 */
public final class RiskScorer {

    private final int escalationThreshold;

    public RiskScorer(final int escalationThreshold) {
        if (escalationThreshold < 1) {
            throw new IllegalArgumentException("escalationThreshold must be >= 1, was " + escalationThreshold);
        }
        this.escalationThreshold = escalationThreshold;
    }

    public RiskLevel score(final Collection<Finding> findings) {
        final RiskLevel highest = findings.stream()
                .map(Finding::severity)
                .max(Comparator.naturalOrder())
                .map(RiskScorer::toRiskLevel)
                .orElse(RiskLevel.NONE);
        if (highest == RiskLevel.NONE || findings.size() < escalationThreshold) {
            return highest;
        }
        return raiseOneLevel(highest);
    }

    private static RiskLevel toRiskLevel(final Severity severity) {
        return RiskLevel.valueOf(severity.name());
    }

    private static RiskLevel raiseOneLevel(final RiskLevel level) {
        final RiskLevel[] levels = RiskLevel.values();
        return levels[Math.min(level.ordinal() + 1, levels.length - 1)];
    }
}
