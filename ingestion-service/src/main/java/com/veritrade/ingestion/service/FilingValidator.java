package com.veritrade.ingestion.service;

import com.veritrade.ingestion.config.IngestionProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Checks a submission against the API rules: company name and title present and within their
 * length, content not blank and at most the configured number of UTF-8 bytes. Lengths are counted in
 * UTF-16 units, as the database columns count them, so a valid value always fits its column. Company
 * name and title are stored without surrounding whitespace.
 */
@Component
public class FilingValidator {

    private final IngestionProperties.FilingLimits limits;

    public FilingValidator(final IngestionProperties properties) {
        this.limits = properties.filing();
    }

    public FilingSubmission validate(final FilingSubmission submission) {
        final List<String> errors = new ArrayList<>();
        final String companyName = checkText("companyName", submission.companyName(), limits.maxCompanyNameLength(), errors);
        final String title = checkText("title", submission.title(), limits.maxTitleLength(), errors);
        checkContent(submission.content(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        return new FilingSubmission(companyName, title, submission.content());
    }

    private static String checkText(final String field, final String value, final int maxLength, final List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + " must not be blank");
            return value;
        }
        final String stripped = value.strip();
        if (stripped.length() > maxLength) {
            errors.add(field + " must be at most " + maxLength + " characters");
        }
        return stripped;
    }

    private void checkContent(final String content, final List<String> errors) {
        if (content == null || content.isBlank()) {
            errors.add("content must not be blank");
            return;
        }
        final int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > limits.maxContentBytes()) {
            errors.add("content must be at most " + limits.maxContentBytes() + " bytes of UTF-8 (was " + bytes + ")");
        }
    }
}
