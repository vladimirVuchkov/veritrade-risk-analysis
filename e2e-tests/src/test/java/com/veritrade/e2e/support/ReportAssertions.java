package com.veritrade.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;

/** Checks that a report is internally consistent and agrees with the filing text it was made from. */
public final class ReportAssertions {

    private static final Comparator<JsonNode> REPORT_ORDER = Comparator
            .comparing((JsonNode finding) -> Severity.valueOf(finding.path("severity").asString()))
            .reversed()
            .thenComparingInt(finding -> finding.path("position").asInt());

    private ReportAssertions() {
    }

    /** A COMPLETED report whose findings, summary, byCategory and bySeverity agree with each other and the text. */
    public static void assertConsistentCompletedReport(final JsonNode report, final String content) {
        Contracts.assertMatchesApiSchema("ReportResponse", report);
        assertThat(report.path("status").asString()).isEqualTo("COMPLETED");
        final List<JsonNode> findings = findings(report);
        final JsonNode summary = report.path("summary");
        assertThat(summary.path("totalFindings").asInt()).isEqualTo(findings.size());
        assertThat(counts(summary.path("byCategory"))).isEqualTo(countBy(findings, "category"));
        assertThat(counts(summary.path("bySeverity"))).isEqualTo(countBy(findings, "severity"));
        assertOverallLevel(RiskLevel.valueOf(summary.path("overallRiskLevel").asString()), findings);
        assertThat(findings).isSortedAccordingTo(REPORT_ORDER);
        assertThat(findings).extracting(ReportAssertions::findingKey).doesNotHaveDuplicates();
        findings.forEach(finding -> assertFindingMatchesText(finding, content));
    }

    public static List<JsonNode> findings(final JsonNode report) {
        return StreamSupport.stream(report.path("findings").spliterator(), false).toList();
    }

    /** ruleId and position identify a finding: the same rule never matches twice at one position. */
    public static String findingKey(final JsonNode finding) {
        return finding.path("ruleId").asString() + "@" + finding.path("position").asInt();
    }

    private static void assertOverallLevel(final RiskLevel overall, final List<JsonNode> findings) {
        if (findings.isEmpty()) {
            assertThat(overall).isEqualTo(RiskLevel.NONE);
            return;
        }
        final Severity highest = findings.stream()
                .map(finding -> Severity.valueOf(finding.path("severity").asString()))
                .max(Comparator.naturalOrder())
                .orElseThrow();
        assertThat(overall).isGreaterThanOrEqualTo(RiskLevel.valueOf(highest.name()));
    }

    private static void assertFindingMatchesText(final JsonNode finding, final String content) {
        final String matched = finding.path("matchedText").asString();
        final int position = finding.path("position").asInt();
        assertThat(content.substring(position, position + matched.length())).as("text at %d", position)
                .isEqualTo(matched);
        assertThat(finding.path("excerpt").asString()).contains(matched);
    }

    private static Map<String, Integer> counts(final JsonNode counts) {
        final Map<String, Integer> result = new TreeMap<>();
        counts.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asInt()));
        return result;
    }

    private static Map<String, Integer> countBy(final List<JsonNode> findings, final String field) {
        return findings.stream().collect(Collectors.groupingBy(finding -> finding.path(field).asString(),
                TreeMap::new, Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));
    }
}
