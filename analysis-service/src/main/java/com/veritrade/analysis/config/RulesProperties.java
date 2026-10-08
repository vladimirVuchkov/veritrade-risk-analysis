package com.veritrade.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/**
 * Settings of the rule engine.
 *
 * @param location            the rules YAML file
 * @param maxMatchesPerRule   at most this many findings per rule and filing
 * @param excerptContextChars characters of context on each side of a match in the excerpt
 * @param maxMatchedTextChars a longer match is cut to this length (the contract allows 500)
 * @param escalationThreshold from this number of findings on, the overall risk level is raised one level
 */
@ConfigurationProperties("veritrade.analysis.rules")
public record RulesProperties(
        @DefaultValue("classpath:risk-rules.yml") Resource location,
        @DefaultValue("50") int maxMatchesPerRule,
        @DefaultValue("120") int excerptContextChars,
        @DefaultValue("500") int maxMatchedTextChars,
        @DefaultValue("10") int escalationThreshold) {
}
