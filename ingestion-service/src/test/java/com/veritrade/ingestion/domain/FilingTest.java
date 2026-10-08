package com.veritrade.ingestion.domain;

import static com.veritrade.ingestion.domain.FilingStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FilingTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    void newFilingIsSubmitted() {
        Filing filing = newFiling();

        assertThat(filing.status()).isEqualTo(SUBMITTED);
        assertThat(filing.updatedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(filing.failureReason()).isNull();
    }

    @Test
    void requiresEveryField() {
        assertThatNullPointerException().isThrownBy(() -> Filing.submit(null, "c", "t", "x", SUBMITTED_AT));
        assertThatNullPointerException().isThrownBy(() -> Filing.submit(UUID.randomUUID(), null, "t", "x", SUBMITTED_AT));
        assertThatNullPointerException().isThrownBy(() -> Filing.submit(UUID.randomUUID(), "c", null, "x", SUBMITTED_AT));
        assertThatNullPointerException().isThrownBy(() -> Filing.submit(UUID.randomUUID(), "c", "t", null, SUBMITTED_AT));
        assertThatNullPointerException().isThrownBy(() -> Filing.submit(UUID.randomUUID(), "c", "t", "x", null));
    }

    @Test
    void viewCarriesEverythingButTheContent() {
        Filing filing = newFiling();

        FilingView view = FilingView.of(filing);

        assertThat(view).isEqualTo(new FilingView(filing.id(), "Acme", "10-K", SUBMITTED, SUBMITTED_AT, null));
    }

    private static Filing newFiling() {
        return Filing.submit(UUID.randomUUID(), "Acme", "10-K", "text", SUBMITTED_AT);
    }
}
