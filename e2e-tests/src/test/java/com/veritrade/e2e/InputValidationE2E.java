package com.veritrade.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.e2e.support.Api;
import com.veritrade.e2e.support.ApiResponse;
import com.veritrade.e2e.support.Contracts;
import com.veritrade.e2e.support.E2ETestBase;
import com.veritrade.e2e.support.FilingRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Scenario 3: input validation of POST /api/filings, through nginx. */
class InputValidationE2E extends E2ETestBase {

    private static final int ACCEPTED = 202;
    private static final int BAD_REQUEST = 400;
    private static final int PAYLOAD_TOO_LARGE = 413;
    private static final int UNSUPPORTED_MEDIA_TYPE = 415;

    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;
    private static final int NGINX_BODY_LIMIT_BYTES = 3 * 1024 * 1024;
    private static final int MAX_COMPANY_NAME_UNITS = 200;
    private static final int MAX_TITLE_UNITS = 300;
    private static final String TWO_BYTE_CHAR = "é";
    private static final int TWO_BYTES = 2;
    /** U+1F4C8 (chart with upwards trend): 4 bytes of UTF-8, two UTF-16 units, one code point. */
    private static final String EMOJI = "\uD83D\uDCC8";
    private static final int EMOJI_BYTES = 4;
    private static final int EMOJI_UNITS = 2;

    private static final FilingRequest VALID = FilingRequest.noRisk("Validation");

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"companyName\":\"\",\"title\":\"\",\"content\":\"\"}",
        "{\"companyName\":\"   \",\"title\":\"\\t\",\"content\":\"\\n \"}",
        "{\"companyName\":null,\"title\":null,\"content\":null}"})
    void blankOrMissingFieldsAreRejectedWithOneErrorPerField(final String body) {
        final ApiResponse response = api.submit(body, Map.of());

        assertBadRequest(response);
        assertThat(response.json().path("errors").toString())
                .contains("companyName", "title", "content");
    }

    @ParameterizedTest
    @CsvSource({"companyName", "title", "content"})
    void eachMissingFieldIsRejected(final String field) {
        final var body = VALID.toJsonNode();
        body.remove(field);

        final ApiResponse response = api.submit(body.toString(), Map.of());

        assertBadRequest(response);
        assertThat(response.json().path("errors").toString()).contains(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "", "null", "[]", "\"text\"", "{\"companyName\":\"A\",}"})
    void malformedJsonIsABadRequestProblem(final String body) {
        assertBadRequest(api.submit(body, Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/xml", "application/x-www-form-urlencoded"})
    void wrongContentTypeIsAnUnsupportedMediaTypeProblem(final String contentType) {
        final ApiResponse response = api.post(Api.FILINGS, BodyPublishers.ofString(VALID.toJson()), contentType, Map.of());

        assertThat(response.isProblem(UNSUPPORTED_MEDIA_TYPE)).as(response.toString()).isTrue();
    }

    @Test
    void missingContentTypeIsAnUnsupportedMediaTypeProblem() {
        final ApiResponse response = api.post(Api.FILINGS, BodyPublishers.ofString(VALID.toJson()), null, Map.of());

        assertThat(response.isProblem(UNSUPPORTED_MEDIA_TYPE)).as(response.toString()).isTrue();
    }

    @Test
    void contentOfExactlyTwoMegabytesIsAccepted() {
        assertAccepted(VALID.withContent("a".repeat(MAX_CONTENT_BYTES)));
    }

    @Test
    void contentOfTwoMegabytesPlusOneByteIsABadRequestNotPayloadTooLarge() {
        final ApiResponse response = api.submit(VALID.withContent("a".repeat(MAX_CONTENT_BYTES + 1)));

        assertBadRequest(response);
        assertThat(response.json().path("errors").toString()).contains("content");
    }

    @Test
    void twoByteCharactersAtTheByteLimitAreAcceptedAndOneMoreByteIsNot() {
        final String atLimit = TWO_BYTE_CHAR.repeat(MAX_CONTENT_BYTES / TWO_BYTES);
        assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_CONTENT_BYTES);

        assertAccepted(VALID.withContent(atLimit));
        assertBadRequest(api.submit(VALID.withContent(atLimit + "a")));
    }

    @Test
    void emojiAtTheByteLimitAreAcceptedAndOneMoreByteIsNot() {
        final String atLimit = EMOJI.repeat(MAX_CONTENT_BYTES / EMOJI_BYTES);
        assertThat(atLimit.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_CONTENT_BYTES);

        assertAccepted(VALID.withContent(atLimit));
        assertBadRequest(api.submit(VALID.withContent(atLimit + "a")));
    }

    @Test
    void companyNameAtTheMaximumLengthIsAcceptedAndOneMoreIsNot() {
        assertAccepted(VALID.withCompanyName("c".repeat(MAX_COMPANY_NAME_UNITS)));
        assertFieldRejected(VALID.withCompanyName("c".repeat(MAX_COMPANY_NAME_UNITS + 1)), "companyName");
    }

    @Test
    void companyNameLengthCountsUtf16UnitsSoEmojiCountTwice() {
        final String atLimit = EMOJI.repeat(MAX_COMPANY_NAME_UNITS / EMOJI_UNITS);

        assertAccepted(VALID.withCompanyName(atLimit));
        assertFieldRejected(VALID.withCompanyName(atLimit + "c"), "companyName");
        assertFieldRejected(VALID.withCompanyName(atLimit + EMOJI), "companyName");
    }

    @Test
    void titleAtTheMaximumLengthIsAcceptedAndOneMoreIsNot() {
        assertAccepted(VALID.withTitle("t".repeat(MAX_TITLE_UNITS)));
        assertFieldRejected(VALID.withTitle("t".repeat(MAX_TITLE_UNITS + 1)), "title");
    }

    @Test
    void titleLengthCountsUtf16UnitsSoEmojiCountTwice() {
        final String atLimit = EMOJI.repeat(MAX_TITLE_UNITS / EMOJI_UNITS);

        assertAccepted(VALID.withTitle(atLimit));
        assertFieldRejected(VALID.withTitle(atLimit + "t"), "title");
    }

    @Test
    void surroundingWhitespaceIsStrippedBeforeTheLengthCheck() {
        final String name = "c".repeat(MAX_COMPANY_NAME_UNITS);

        final UUID filingId = assertAccepted(VALID.withCompanyName("  " + name + "\t"));

        assertThat(api.filing(filingId).json().path("companyName").asString()).isEqualTo(name);
    }

    @Test
    void bodyOverTheNginxLimitIsAPayloadTooLargeProblem() {
        final ApiResponse response = api.submit(VALID.withContent("a".repeat(NGINX_BODY_LIMIT_BYTES + 1)));

        assertThat(response.isProblem(PAYLOAD_TOO_LARGE)).as(response.toString()).isTrue();
    }

    private static UUID assertAccepted(final FilingRequest filing) {
        final ApiResponse response = api.submit(filing);
        assertThat(response.status()).as(response.toString()).isEqualTo(ACCEPTED);
        final UUID filingId = UUID.fromString(response.json().path("filingId").asString());
        assertThat(api.filing(filingId).json().path("title").asString()).isEqualTo(filing.title().strip());
        return filingId;
    }

    private static void assertFieldRejected(final FilingRequest filing, final String field) {
        final ApiResponse response = api.submit(filing);
        assertBadRequest(response);
        assertThat(response.json().path("errors").toString()).contains(field);
    }

    private static void assertBadRequest(final ApiResponse response) {
        assertThat(response.isProblem(BAD_REQUEST)).as(response.toString()).isTrue();
        Contracts.assertMatchesApiSchema("ProblemDetail", response.json());
    }
}
