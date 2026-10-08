package com.veritrade.analysis.engine;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** A validated rule with its precompiled, case-insensitive patterns. */
public record RiskRule(String id, RiskCategory category, Severity severity, List<Pattern> patterns) {

    public RiskRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        patterns = List.copyOf(patterns);
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Rule " + id + " has no patterns");
        }
    }
}
