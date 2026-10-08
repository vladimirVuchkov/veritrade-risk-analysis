package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import com.veritrade.e2e.support.ReportAssertions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * W3-03: plain-text EDGAR filings are hard-wrapped, so a risk phrase is often broken by a line break.
 * The wrapped sample must give the same findings as the unwrapped one, with positions in the wrapped text.
 */
class HardWrappedFilingE2E extends E2ETestBase {

    private static final int WRAP_COLUMNS = 72;

    @Test
    void hardWrappedSampleGivesTheSameRisksAsTheUnwrappedSample() {
        final FilingRequest unwrapped = system.demoFiling().withTitle("Unwrapped " + UUID.randomUUID());
        final FilingRequest wrapped = unwrapped.withTitle("Wrapped " + UUID.randomUUID())
                .withContent(wrap(unwrapped.content(), WRAP_COLUMNS));
        assertThat(wrapped.content()).isNotEqualTo(unwrapped.content());

        final JsonNode expected = api.awaitReport(api.submitAccepted(unwrapped));
        final JsonNode actual = api.awaitReport(api.submitAccepted(wrapped));

        ReportAssertions.assertConsistentCompletedReport(actual, wrapped.content());
        assertThat(ruleIds(actual)).containsExactlyInAnyOrderElementsOf(ruleIds(expected));
        assertThat(actual.path("summary")).isEqualTo(expected.path("summary"));
        assertThat(ReportAssertions.findings(actual))
                .anySatisfy(finding -> assertThat(finding.path("matchedText").asString()).contains("\n"));
    }

    private static List<String> ruleIds(final JsonNode report) {
        return ReportAssertions.findings(report).stream().map(finding -> finding.path("ruleId").asString()).toList();
    }

    /** Greedy word wrap of every line, like a plain-text EDGAR filing. */
    private static String wrap(final String text, final int columns) {
        final StringBuilder out = new StringBuilder();
        for (final String line : text.split("\n", -1)) {
            int lineLength = 0;
            for (final String word : line.split(" ")) {
                if (lineLength > 0 && lineLength + 1 + word.length() > columns) {
                    out.append('\n');
                    lineLength = 0;
                } else if (lineLength > 0) {
                    out.append(' ');
                    lineLength++;
                }
                out.append(word);
                lineLength += word.length();
            }
            out.append('\n');
        }
        return out.substring(0, out.length() - 1);
    }
}
