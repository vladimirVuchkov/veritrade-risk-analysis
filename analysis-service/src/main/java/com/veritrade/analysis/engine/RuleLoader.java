package com.veritrade.analysis.engine;

import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.Severity;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads the rules YAML, validates every rule and precompiles its patterns. Any problem makes
 * {@link #load} throw a {@link RuleValidationException} that lists all problems, so a broken rules
 * file stops the service at startup instead of producing wrong results later.
 */
public final class RuleLoader {

    static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern RULE_ID_FORMAT = Pattern.compile("[A-Z]+-[0-9]{3}");
    private static final int MAX_RULES_VERSION_LENGTH = 32;
    private static final List<String> DOCUMENT_KEYS = List.of("rulesVersion", "rules");
    private static final List<String> RULE_KEYS = List.of("id", "category", "severity", "patterns");

    public RuleSet load(InputStream in, String source) {
        Objects.requireNonNull(in, "in");
        List<String> errors = new ArrayList<>();
        Map<?, ?> document = parse(in, errors);
        if (document == null) {
            throw new RuleValidationException(source, errors);
        }
        checkKeys(document, DOCUMENT_KEYS, "the file", errors);
        String rulesVersion = readRulesVersion(document.get("rulesVersion"), errors);
        List<RiskRule> rules = readRules(document.get("rules"), errors);
        if (!errors.isEmpty()) {
            throw new RuleValidationException(source, errors);
        }
        return new RuleSet(rulesVersion, rules);
    }

    private static Map<?, ?> parse(InputStream in, List<String> errors) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            Object document = new Yaml(new SafeConstructor(options)).load(in);
            if (document instanceof Map<?, ?> map) {
                return map;
            }
            errors.add("the file must be a mapping with the keys 'rulesVersion' and 'rules'");
        } catch (YAMLException e) {
            errors.add("not valid YAML: " + e.getMessage());
        }
        return null;
    }

    private static String readRulesVersion(Object value, List<String> errors) {
        if (!(value instanceof String version) || version.isBlank()) {
            errors.add("'rulesVersion' must be a non-empty quoted string");
            return null;
        }
        if (version.length() > MAX_RULES_VERSION_LENGTH) {
            errors.add("'rulesVersion' must be at most " + MAX_RULES_VERSION_LENGTH + " characters");
        }
        return version;
    }

    private static List<RiskRule> readRules(Object value, List<String> errors) {
        if (!(value instanceof List<?> entries) || entries.isEmpty()) {
            errors.add("'rules' must be a non-empty list");
            return List.of();
        }
        List<RiskRule> rules = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            readRule(entries.get(i), i + 1, seenIds, errors).ifPresent(rules::add);
        }
        return rules;
    }

    private static Optional<RiskRule> readRule(Object entry, int number, Set<String> seenIds, List<String> errors) {
        String label = "rule #" + number;
        if (!(entry instanceof Map<?, ?> fields)) {
            errors.add(label + ": must be a mapping");
            return Optional.empty();
        }
        int errorsBefore = errors.size();
        String id = readId(fields.get("id"), label, seenIds, errors);
        String where = id == null ? label : label + " (" + id + ")";
        checkKeys(fields, RULE_KEYS, where, errors);
        RiskCategory category = readEnum(fields, "category", RiskCategory.class, where, errors);
        Severity severity = readEnum(fields, "severity", Severity.class, where, errors);
        List<Pattern> patterns = readPatterns(fields.get("patterns"), where, errors);
        if (errors.size() != errorsBefore) {
            return Optional.empty();
        }
        return Optional.of(new RiskRule(id, category, severity, patterns));
    }

    private static String readId(Object value, String label, Set<String> seenIds, List<String> errors) {
        if (!(value instanceof String id) || !RULE_ID_FORMAT.matcher(id).matches()) {
            errors.add(label + ": 'id' must look like LEGAL-001 (upper-case letters, '-', three digits), was " + value);
            return null;
        }
        if (!seenIds.add(id)) {
            errors.add(label + ": duplicate rule id " + id);
        }
        return id;
    }

    private static <E extends Enum<E>> E readEnum(
            Map<?, ?> fields, String key, Class<E> type, String where, List<String> errors) {
        Object value = fields.get(key);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) {
                return constant;
            }
        }
        errors.add(where + ": unknown " + key + " '" + value + "', expected one of "
                + Arrays.toString(type.getEnumConstants()));
        return null;
    }

    private static List<Pattern> readPatterns(Object value, String where, List<String> errors) {
        if (!(value instanceof List<?> sources) || sources.isEmpty()) {
            errors.add(where + ": 'patterns' must be a non-empty list");
            return List.of();
        }
        List<Pattern> patterns = new ArrayList<>();
        for (Object source : sources) {
            compile(source, where, errors).ifPresent(patterns::add);
        }
        return patterns;
    }

    private static Optional<Pattern> compile(Object source, String where, List<String> errors) {
        if (!(source instanceof String regex) || regex.isBlank()) {
            errors.add(where + ": every pattern must be a non-blank string, was " + source);
            return Optional.empty();
        }
        try {
            Pattern pattern = Pattern.compile(regex, PATTERN_FLAGS);
            if (pattern.matcher("").matches()) {
                errors.add(where + ": pattern '" + regex + "' matches empty text");
                return Optional.empty();
            }
            return Optional.of(pattern);
        } catch (PatternSyntaxException e) {
            errors.add(where + ": invalid regex '" + regex + "': " + e.getDescription() + " near index " + e.getIndex());
            return Optional.empty();
        }
    }

    private static void checkKeys(Map<?, ?> fields, List<String> allowed, String where, List<String> errors) {
        for (Object key : fields.keySet()) {
            if (!allowed.contains(key)) {
                errors.add(where + ": unknown key '" + key + "', allowed keys are " + allowed);
            }
        }
    }
}
