package com.veritrade.analysis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

/**
 * Settings of the rule engine. Lengths count UTF-16 code units, like the {@code maxLength} of the schemas.
 *
 * @param location            the rules YAML file
 * @param maxMatchesPerRule   at most this many findings per rule and filing
 * @param excerptContextChars characters of context on each side of a match in the excerpt
 * @param maxMatchedTextChars a longer match is cut to this length; at most {@value #SCHEMA_MAX_MATCHED_TEXT}
 * @param maxExcerptChars     an excerpt is at most this long (the context shrinks for a long match);
 *                            at least {@code maxMatchedTextChars}, at most {@value #SCHEMA_MAX_EXCERPT}
 * @param escalationThreshold from this number of findings on, the overall risk level is raised one level
 */
@ConfigurationProperties("veritrade.analysis.rules")
public record RulesProperties(
        @DefaultValue("classpath:risk-rules.yml") Resource location,
        @DefaultValue("50") int maxMatchesPerRule,
        @DefaultValue("120") int excerptContextChars,
        @DefaultValue("500") int maxMatchedTextChars,
        @DefaultValue("1000") int maxExcerptChars,
        @DefaultValue("10") int escalationThreshold) {

    /** {@code maxLength} of {@code matchedText} in analysis-completed.schema.json. */
    public static final int SCHEMA_MAX_MATCHED_TEXT = 500;
    /** {@code maxLength} of {@code excerpt} in analysis-completed.schema.json. */
    public static final int SCHEMA_MAX_EXCERPT = 1000;

    public RulesProperties {
        if (maxMatchedTextChars > SCHEMA_MAX_MATCHED_TEXT) {
            throw new IllegalArgumentException("maxMatchedTextChars must be at most " + SCHEMA_MAX_MATCHED_TEXT
                    + ", was " + maxMatchedTextChars);
        }
        if (maxExcerptChars < maxMatchedTextChars || maxExcerptChars > SCHEMA_MAX_EXCERPT) {
            throw new IllegalArgumentException("maxExcerptChars must be between maxMatchedTextChars ("
                    + maxMatchedTextChars + ") and " + SCHEMA_MAX_EXCERPT + ", was " + maxExcerptChars);
        }
    }
}
