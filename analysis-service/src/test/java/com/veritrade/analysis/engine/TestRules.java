package com.veritrade.analysis.engine;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Rule builders and the bundled rules file for engine tests. */
final class TestRules {

    static final int DEFAULT_CAP = 50;
    static final int DEFAULT_CONTEXT = 120;
    static final int DEFAULT_MAX_MATCH = 500;
    static final int DEFAULT_MAX_EXCERPT = 1000;
    static final int DEFAULT_THRESHOLD = 10;
    static final int TWO_MB = 2 * 1024 * 1024;
    /**
     * Wall-clock limit for analysing 2 MB. About 1 s on a laptop and about 50 s even with the JIT
     * compiler turned off ({@code -Xint}), so a slow or busy CI runner never fails it. It only stops a
     * runaway match; {@link CountingText} checks the complexity independently of the machine.
     */
    static final Duration GENEROUS_TIME_LIMIT = Duration.ofSeconds(60);

    private TestRules() {
    }

    static RiskRule rule(String id, RiskCategory category, Severity severity, String... patterns) {
        List<Pattern> compiled = Arrays.stream(patterns).map(p -> Pattern.compile(p, RuleLoader.PATTERN_FLAGS)).toList();
        return new RiskRule(id, category, severity, compiled);
    }

    static RuleSet bundledRules() {
        try (InputStream in = TestRules.class.getClassLoader().getResourceAsStream("risk-rules.yml")) {
            return new RuleLoader().load(in, "risk-rules.yml");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RuleSet load(String yaml) {
        return new RuleLoader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");
    }

    static RiskAnalyzer analyzer(RuleSet rules) {
        return new RiskAnalyzer(rules, new RuleMatcher(DEFAULT_CAP, DEFAULT_MAX_MATCH),
                new ExcerptExtractor(DEFAULT_CONTEXT, DEFAULT_MAX_EXCERPT), new RiskScorer(DEFAULT_THRESHOLD));
    }

    static RiskAnalyzer bundledAnalyzer() {
        return analyzer(bundledRules());
    }
}
