package com.veritrade.analysis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuleLoaderTest {

    private static final String VALID_RULE = """
              - id: LEGAL-001
                category: LEGAL
                severity: HIGH
                patterns:
                  - 'pending litigation'
            """;

    @Test
    void bundledRulesHaveAtLeastTwentyRulesCoveringEveryCategory() {
        RuleSet rules = TestRules.bundledRules();

        assertThat(rules.rulesVersion()).isEqualTo("1.0");
        assertThat(rules.rules()).hasSizeGreaterThanOrEqualTo(20);
        assertThat(rules.rules()).extracting(RiskRule::category).contains(RiskCategory.values());
        assertThat(rules.rules()).extracting(RiskRule::severity).contains(Severity.values());
        assertThat(rules.rules()).extracting(RiskRule::id).doesNotHaveDuplicates();
    }

    @Test
    void loadsAValidRuleWithCaseInsensitivePatterns() {
        RuleSet rules = TestRules.load("rulesVersion: \"2.1\"\nrules:\n" + VALID_RULE);

        assertThat(rules.rulesVersion()).isEqualTo("2.1");
        RiskRule rule = rules.rules().getFirst();
        assertThat(rule.id()).isEqualTo("LEGAL-001");
        assertThat(rule.category()).isEqualTo(RiskCategory.LEGAL);
        assertThat(rule.severity()).isEqualTo(Severity.HIGH);
        assertThat(rule.patterns()).singleElement()
                .satisfies(p -> assertThat(p.matcher("PENDING Litigation").matches()).isTrue());
    }

    @Test
    void keepsTheOrderOfRulesAndPatterns() {
        RuleSet rules = TestRules.load("""
                rulesVersion: "1"
                rules:
                  - id: B-002
                    category: MARKET
                    severity: LOW
                    patterns: ['second', 'third']
                  - id: A-001
                    category: LEGAL
                    severity: LOW
                    patterns: ['first']
                """);

        assertThat(rules.rules()).extracting(RiskRule::id).containsExactly("B-002", "A-001");
        assertThat(rules.rules().getFirst().patterns()).extracting(Object::toString).containsExactly("second", "third");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRuleFiles")
    void refusesAnInvalidRulesFileWithAClearMessage(String problem, String yaml, String expectedMessage) {
        assertThatThrownBy(() -> TestRules.load(yaml))
                .isInstanceOf(RuleValidationException.class)
                .hasMessageContaining("Invalid risk rules in test.yml")
                .hasMessageContaining(expectedMessage);
    }

    static Stream<Arguments> invalidRuleFiles() {
        return Stream.of(
                Arguments.of("invalid regex", rules(rule("LEGAL-001", "LEGAL", "HIGH", "'pending (litigation'")),
                        "rule #1 (LEGAL-001): invalid regex 'pending (litigation'"),
                Arguments.of("duplicate rule id", rules(rule("LEGAL-001", "LEGAL", "HIGH", "'a1'")
                        + rule("LEGAL-001", "LEGAL", "LOW", "'b2'")), "rule #2: duplicate rule id LEGAL-001"),
                Arguments.of("unknown category", rules(rule("LEGAL-001", "CLIMATE", "HIGH", "'x1'")),
                        "unknown category 'CLIMATE'"),
                Arguments.of("lower-case category", rules(rule("LEGAL-001", "legal", "HIGH", "'x1'")),
                        "unknown category 'legal'"),
                Arguments.of("unknown severity", rules(rule("LEGAL-001", "LEGAL", "SEVERE", "'x1'")),
                        "unknown severity 'SEVERE', expected one of [LOW, MEDIUM, HIGH, CRITICAL]"),
                Arguments.of("missing severity", rules("""
                          - id: LEGAL-001
                            category: LEGAL
                            patterns: ['x1']
                        """), "unknown severity 'null'"),
                Arguments.of("empty pattern list", rules("""
                          - id: LEGAL-001
                            category: LEGAL
                            severity: LOW
                            patterns: []
                        """), "'patterns' must be a non-empty list"),
                Arguments.of("missing patterns", rules("""
                          - id: LEGAL-001
                            category: LEGAL
                            severity: LOW
                        """), "'patterns' must be a non-empty list"),
                Arguments.of("blank pattern", rules(rule("LEGAL-001", "LEGAL", "LOW", "'   '")),
                        "every pattern must be a non-blank string"),
                Arguments.of("non-string pattern", rules(rule("LEGAL-001", "LEGAL", "LOW", "42")),
                        "every pattern must be a non-blank string, was 42"),
                Arguments.of("pattern matching empty text", rules(rule("LEGAL-001", "LEGAL", "LOW", "'(?:risk)?'")),
                        "pattern '(?:risk)?' matches empty text"),
                Arguments.of("malformed rule id", rules(rule("legal-1", "LEGAL", "LOW", "'x1'")),
                        "'id' must look like LEGAL-001"),
                Arguments.of("missing rule id", rules("""
                          - category: LEGAL
                            severity: LOW
                            patterns: ['x1']
                        """), "rule #1: 'id' must look like LEGAL-001"),
                Arguments.of("unknown rule key", rules(rule("LEGAL-001", "LEGAL", "LOW", "'x1'") + "    pattern: 'typo'\n"),
                        "unknown key 'pattern'"),
                Arguments.of("rule is not a mapping", rules("  - just a string\n"), "rule #1: must be a mapping"),
                Arguments.of("missing rulesVersion", "rules:\n" + VALID_RULE, "'rulesVersion' must be a non-empty quoted string"),
                Arguments.of("numeric rulesVersion", "rulesVersion: 1.0\nrules:\n" + VALID_RULE,
                        "'rulesVersion' must be a non-empty quoted string"),
                Arguments.of("too long rulesVersion", "rulesVersion: \"" + "9".repeat(33) + "\"\nrules:\n" + VALID_RULE,
                        "'rulesVersion' must be at most 32 characters"),
                Arguments.of("empty rule list", "rulesVersion: \"1\"\nrules: []\n", "'rules' must be a non-empty list"),
                Arguments.of("missing rule list", "rulesVersion: \"1\"\n", "'rules' must be a non-empty list"),
                Arguments.of("unknown top-level key", "rulesVersion: \"1\"\nversion: 2\nrules:\n" + VALID_RULE,
                        "the file: unknown key 'version'"),
                Arguments.of("document is a list", "- a\n- b\n", "the file must be a mapping"),
                Arguments.of("empty file", "", "the file must be a mapping"),
                Arguments.of("malformed YAML", "rulesVersion: \"1\"\nrules: [unclosed\n", "not valid YAML"),
                Arguments.of("duplicate YAML key", "rulesVersion: \"1\"\nrulesVersion: \"2\"\nrules:\n" + VALID_RULE,
                        "not valid YAML"));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        String yaml = rules(rule("LEGAL-001", "CLIMATE", "HIGH", "'ok1'")
                + rule("LEGAL-002", "LEGAL", "SEVERE", "'bad ['"));

        assertThatThrownBy(() -> TestRules.load(yaml))
                .isInstanceOfSatisfying(RuleValidationException.class, e -> assertThat(e.errors())
                        .hasSize(3)
                        .anyMatch(m -> m.contains("unknown category 'CLIMATE'"))
                        .anyMatch(m -> m.contains("unknown severity 'SEVERE'"))
                        .anyMatch(m -> m.contains("invalid regex 'bad ['")));
    }

    @Test
    void namesTheSourceInTheMessage() {
        ByteArrayInputStream in = new ByteArrayInputStream("rules: []".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new RuleLoader().load(in, "class path resource [broken.yml]"))
                .hasMessageStartingWith("Invalid risk rules in class path resource [broken.yml]:");
    }

    @Test
    void everyCategoryAndSeverityNameIsAccepted() {
        Set<String> loaded = new HashSet<>();
        for (RiskCategory category : RiskCategory.values()) {
            for (Severity severity : Severity.values()) {
                String id = category.name() + "-00" + (severity.ordinal() + 1);
                RuleSet set = TestRules.load(rules(rule(id, category.name(), severity.name(), "'x1'")));
                loaded.add(set.rules().getFirst().category() + "/" + set.rules().getFirst().severity());
            }
        }

        assertThat(loaded).hasSize(RiskCategory.values().length * Severity.values().length);
    }

    @Test
    void enumNamesInMessagesAreTheContractNames() {
        String categories = Arrays.stream(RiskCategory.values()).map(Enum::name).collect(Collectors.joining(", "));

        assertThatThrownBy(() -> TestRules.load(rules(rule("A-001", "X", "LOW", "'x1'"))))
                .hasMessageContaining("expected one of [" + categories + "]");
    }

    private static String rules(String ruleEntries) {
        return "rulesVersion: \"1.0\"\nrules:\n" + ruleEntries;
    }

    private static String rule(String id, String category, String severity, String pattern) {
        return """
                  - id: %s
                    category: %s
                    severity: %s
                    patterns:
                      - %s
                """.formatted(id, category, severity, pattern);
    }
}
