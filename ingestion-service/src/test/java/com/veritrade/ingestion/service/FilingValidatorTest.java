package com.veritrade.ingestion.service;

import static com.veritrade.ingestion.support.TestProperties.MAX_COMPANY_NAME_LENGTH;
import static com.veritrade.ingestion.support.TestProperties.MAX_CONTENT_BYTES;
import static com.veritrade.ingestion.support.TestProperties.MAX_TITLE_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.ingestion.support.TestProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class FilingValidatorTest {

    /** U+20AC EURO SIGN: 3 bytes in UTF-8, one UTF-16 char. */
    private static final String EURO = "€";
    /** U+1F600: 4 bytes in UTF-8, two UTF-16 chars, one code point. */
    private static final String EMOJI = "😀";

    private final FilingValidator validator = new FilingValidator(TestProperties.defaults());

    @Test
    void acceptsAValidSubmission() {
        final FilingSubmission valid = validator.validate(new FilingSubmission("Acme", "10-K", "text"));

        assertThat(valid).isEqualTo(new FilingSubmission("Acme", "10-K", "text"));
    }

    @Test
    void stripsCompanyNameAndTitleButKeepsContentAsIs() {
        final FilingSubmission valid = validator.validate(new FilingSubmission("  Acme \t", "\n10-K ", "  text  "));

        assertThat(valid).isEqualTo(new FilingSubmission("Acme", "10-K", "  text  "));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\r\n ", " "})
    void rejectsMissingOrBlankCompanyName(final String companyName) {
        assertRejected(new FilingSubmission(companyName, "10-K", "text"), "companyName must not be blank");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsMissingOrBlankTitle(final String title) {
        assertRejected(new FilingSubmission("Acme", title, "text"), "title must not be blank");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\n\n\t  "})
    void rejectsMissingOrBlankContent(final String content) {
        assertRejected(new FilingSubmission("Acme", "10-K", content), "content must not be blank");
    }

    @Test
    void reportsEveryErrorAtOnce() {
        assertThatThrownBy(() -> validator.validate(new FilingSubmission(null, " ", "")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.errors()).containsExactly(
                        "companyName must not be blank", "title must not be blank", "content must not be blank"));
    }

    @Test
    void acceptsContentOfExactlyTheLimit() {
        final String content = "a".repeat(MAX_CONTENT_BYTES);

        assertThat(validator.validate(new FilingSubmission("Acme", "10-K", content)).content()).hasSize(MAX_CONTENT_BYTES);
    }

    @Test
    void rejectsContentOneByteOverTheLimit() {
        final String content = "a".repeat(MAX_CONTENT_BYTES + 1);

        assertRejected(new FilingSubmission("Acme", "10-K", content),
                "content must be at most 2097152 bytes of UTF-8 (was 2097153)");
    }

    @Test
    void measuresContentInUtf8BytesNotCharacters() {
        final String exactly = "a".repeat(MAX_CONTENT_BYTES - 3) + EURO;
        final String overByOne = "a".repeat(MAX_CONTENT_BYTES - 2) + EURO;

        assertThat(exactly.getBytes(StandardCharsets.UTF_8)).hasSize(MAX_CONTENT_BYTES);
        assertThat(validator.validate(new FilingSubmission("Acme", "10-K", exactly)).content()).isEqualTo(exactly);
        assertRejected(new FilingSubmission("Acme", "10-K", overByOne),
                "content must be at most 2097152 bytes of UTF-8 (was 2097153)");
    }

    @Test
    void rejectsMultibyteContentWhoseCharacterCountIsFarBelowTheLimit() {
        final String content = EMOJI.repeat(MAX_CONTENT_BYTES / 4 + 1);

        assertThat(content.codePointCount(0, content.length())).isLessThan(MAX_CONTENT_BYTES);
        assertRejected(new FilingSubmission("Acme", "10-K", content),
                "content must be at most 2097152 bytes of UTF-8 (was 2097156)");
    }

    @Test
    void acceptsFourByteCharactersUpToExactlyTheLimit() {
        final String content = EMOJI.repeat(MAX_CONTENT_BYTES / 4);

        assertThat(validator.validate(new FilingSubmission("Acme", "10-K", content)).content()).isEqualTo(content);
    }

    @Test
    void acceptsCompanyNameOfExactlyTheLimit() {
        final String name = "c".repeat(MAX_COMPANY_NAME_LENGTH);

        assertThat(validator.validate(new FilingSubmission(name, "10-K", "text")).companyName()).isEqualTo(name);
    }

    @Test
    void rejectsCompanyNameOverTheLimit() {
        assertRejected(new FilingSubmission("c".repeat(MAX_COMPANY_NAME_LENGTH + 1), "10-K", "text"),
                "companyName must be at most 200 characters");
    }

    @Test
    void countsCompanyNameInUtf16UnitsSoItAlwaysFitsTheColumn() {
        final String atLimit = EMOJI.repeat(MAX_COMPANY_NAME_LENGTH / 2);

        assertThat(validator.validate(new FilingSubmission(atLimit, "10-K", "text")).companyName()).isEqualTo(atLimit);
        assertRejected(new FilingSubmission(atLimit + "a", "10-K", "text"), "companyName must be at most 200 characters");
    }

    @Test
    void measuresCompanyNameAfterStripping() {
        final String padded = "  " + "c".repeat(MAX_COMPANY_NAME_LENGTH) + "  ";

        assertThat(validator.validate(new FilingSubmission(padded, "10-K", "text")).companyName())
                .hasSize(MAX_COMPANY_NAME_LENGTH);
    }

    @Test
    void acceptsTitleOfExactlyTheLimitAndRejectsOneMore() {
        final String title = "t".repeat(MAX_TITLE_LENGTH);

        assertThat(validator.validate(new FilingSubmission("Acme", title, "text")).title()).isEqualTo(title);
        assertRejected(new FilingSubmission("Acme", title + "t", "text"), "title must be at most 300 characters");
    }

    @Test
    void countsTitleInUtf16Units() {
        final String bmp = EURO.repeat(MAX_TITLE_LENGTH);
        final String astral = EMOJI.repeat(MAX_TITLE_LENGTH / 2);

        assertThat(validator.validate(new FilingSubmission("Acme", bmp, "text")).title()).isEqualTo(bmp);
        assertThat(validator.validate(new FilingSubmission("Acme", astral, "text")).title()).isEqualTo(astral);
        assertRejected(new FilingSubmission("Acme", astral + EMOJI, "text"), "title must be at most 300 characters");
    }

    private void assertRejected(final FilingSubmission submission, final String error) {
        assertThatThrownBy(() -> validator.validate(submission))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.errors()).containsExactly(error));
    }
}
