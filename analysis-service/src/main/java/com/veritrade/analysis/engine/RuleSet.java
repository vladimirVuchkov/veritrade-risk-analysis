package com.veritrade.analysis.engine;

import java.util.List;
import java.util.Objects;

/** All rules loaded from one rules file, with the version reported in every analysis result. */
public record RuleSet(String rulesVersion, List<RiskRule> rules) {

    public RuleSet {
        Objects.requireNonNull(rulesVersion, "rulesVersion");
        rules = List.copyOf(rules);
    }
}
