package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.Contracts;
import com.veritrade.e2e.support.DeadLetters;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import com.veritrade.e2e.support.ReportAssertions;
import com.veritrade.e2e.support.Timeouts;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A filing at the 2 MB content limit goes all the way through the system. Risk phrases at the start, in
 * the middle and at the very end prove that no service cut the text on its way to the rule engine.
 */
class LargeFilingE2E extends E2ETestBase {

    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;
    private static final String START_PHRASE = "going concern";
    private static final String MIDDLE_PHRASE = "pending litigation";
    private static final String END_PHRASE = "economic sanctions";
    private static final List<String> EXPECTED_RULE_IDS = List.of("FIN-001", "LEGAL-001", "REG-004");
    private static final String ASCII_FILLER =
            "The company held its annual picnic in the park and everyone enjoyed the lemonade. ";
    /** Cyrillic (2 bytes of UTF-8 each) and U+1F4C8 (4 bytes, two UTF-16 units), so bytes, units and code points differ. */
    private static final String MULTIBYTE_FILLER = "Компания провела ежегодный пикник в парке 📈 и все пили лимонад. ";
    private static final String BYTE_FILLER = " ";

    @Test
    void asciiFilingOfExactlyTwoMegabytesIsAnalysedUpToItsLastByte() {
        assertAnalysedUpToTheLastByte(contentOfExactly(MAX_CONTENT_BYTES, ASCII_FILLER));
    }

    @Test
    void multibyteFilingOfExactlyTwoMegabytesIsAnalysedUpToItsLastByte() {
        assertAnalysedUpToTheLastByte(contentOfExactly(MAX_CONTENT_BYTES, MULTIBYTE_FILLER));
    }

    private static void assertAnalysedUpToTheLastByte(String content) {
        assertThat(utf8Bytes(content)).isEqualTo(MAX_CONTENT_BYTES);
        FilingRequest filing = FilingRequest.noRisk("Large filing " + UUID.randomUUID()).withContent(content);
        UUID filingId = api.submitAccepted(filing);

        JsonNode status = api.awaitStatus(filingId, "COMPLETED", Timeouts.LARGE_FILING_PROCESSING);
        Contracts.assertMatchesApiSchema("FilingStatusResponse", status);
        JsonNode report = api.awaitReport(filingId, Timeouts.LARGE_FILING_PROCESSING);

        ReportAssertions.assertConsistentCompletedReport(report, content);
        List<JsonNode> findings = ReportAssertions.findings(report);
        assertThat(findings).extracting(finding -> finding.path("ruleId").asString())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_RULE_IDS);
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.path("matchedText").asString()).isEqualTo(END_PHRASE);
            assertThat(finding.path("position").asInt()).isEqualTo(content.length() - END_PHRASE.length());
        });
        DeadLetters.assertNothingDeadLetteredFor(broker, filingId.toString());
    }

    /** START_PHRASE, filler, MIDDLE_PHRASE halfway, filler, END_PHRASE as the last bytes. */
    private static String contentOfExactly(int bytes, String filler) {
        String head = START_PHRASE + " ";
        String middle = " " + MIDDLE_PHRASE + " ";
        String tail = " " + END_PHRASE;
        int padding = bytes - utf8Bytes(head) - utf8Bytes(middle) - utf8Bytes(tail);
        int firstHalf = padding / 2;
        return head + padding(firstHalf, filler) + middle + padding(padding - firstHalf, filler) + tail;
    }

    private static String padding(int bytes, String filler) {
        int copies = bytes / utf8Bytes(filler);
        return filler.repeat(copies) + BYTE_FILLER.repeat(bytes - copies * utf8Bytes(filler));
    }

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
