package com.veritrade.ingestion.domain;

import static com.veritrade.ingestion.domain.FilingStatus.ANALYZING;
import static com.veritrade.ingestion.domain.FilingStatus.COMPLETED;
import static com.veritrade.ingestion.domain.FilingStatus.FAILED;
import static com.veritrade.ingestion.domain.FilingStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FilingTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-07T12:00:05Z");

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

    static Stream<Arguments> everyChange() {
        return Stream.of(
                Arguments.of(List.of(), ANALYZING, StatusChange.APPLIED, ANALYZING),
                Arguments.of(List.of(), COMPLETED, StatusChange.APPLIED, COMPLETED),
                Arguments.of(List.of(), FAILED, StatusChange.APPLIED, FAILED),
                Arguments.of(List.of(), SUBMITTED, StatusChange.DUPLICATE, SUBMITTED),
                Arguments.of(List.of(ANALYZING), ANALYZING, StatusChange.DUPLICATE, ANALYZING),
                Arguments.of(List.of(ANALYZING), COMPLETED, StatusChange.APPLIED, COMPLETED),
                Arguments.of(List.of(ANALYZING), FAILED, StatusChange.APPLIED, FAILED),
                Arguments.of(List.of(ANALYZING), SUBMITTED, StatusChange.REJECTED, ANALYZING),
                Arguments.of(List.of(COMPLETED), ANALYZING, StatusChange.REJECTED, COMPLETED),
                Arguments.of(List.of(COMPLETED), FAILED, StatusChange.REJECTED, COMPLETED),
                Arguments.of(List.of(COMPLETED), COMPLETED, StatusChange.DUPLICATE, COMPLETED),
                Arguments.of(List.of(COMPLETED), SUBMITTED, StatusChange.REJECTED, COMPLETED),
                Arguments.of(List.of(FAILED), ANALYZING, StatusChange.REJECTED, FAILED),
                Arguments.of(List.of(FAILED), COMPLETED, StatusChange.REJECTED, FAILED),
                Arguments.of(List.of(FAILED), FAILED, StatusChange.DUPLICATE, FAILED),
                Arguments.of(List.of(FAILED), SUBMITTED, StatusChange.REJECTED, FAILED),
                Arguments.of(List.of(ANALYZING, COMPLETED), ANALYZING, StatusChange.REJECTED, COMPLETED),
                Arguments.of(List.of(ANALYZING, FAILED), COMPLETED, StatusChange.REJECTED, FAILED));
    }

    @ParameterizedTest(name = "after {0}, {1} -> {2}, status {3}")
    @MethodSource("everyChange")
    void changesStatusOnlyWhenTheLifecycleAllows(
            List<FilingStatus> history, FilingStatus target, StatusChange expected, FilingStatus statusAfter) {
        Filing filing = withHistory(history);

        StatusChange change = filing.changeStatus(target, "reason", LATER);

        assertThat(change).isEqualTo(expected);
        assertThat(filing.status()).isEqualTo(statusAfter);
    }

    @Test
    void appliedChangeUpdatesTheTimestamp() {
        Filing filing = newFiling();

        filing.changeStatus(ANALYZING, null, LATER);

        assertThat(filing.updatedAt()).isEqualTo(LATER);
    }

    @Test
    void rejectedOrDuplicateChangeLeavesTheFilingUntouched() {
        Filing filing = withHistory(List.of(COMPLETED));
        Instant updatedAt = filing.updatedAt();

        filing.changeStatus(FAILED, "too late", LATER.plusSeconds(10));
        filing.changeStatus(COMPLETED, null, LATER.plusSeconds(20));

        assertThat(filing.updatedAt()).isEqualTo(updatedAt);
        assertThat(filing.failureReason()).isNull();
    }

    @Test
    void keepsTheFailureReasonOnlyWhenFailed() {
        Filing failed = newFiling();
        Filing completed = newFiling();

        failed.changeStatus(FAILED, "rule engine error", LATER);
        completed.changeStatus(COMPLETED, "ignored", LATER);

        assertThat(failed.failureReason()).isEqualTo("rule engine error");
        assertThat(completed.failureReason()).isNull();
    }

    @Test
    void viewCarriesEverythingButTheContent() {
        Filing filing = newFiling();
        filing.changeStatus(FAILED, "boom", LATER);

        FilingView view = FilingView.of(filing);

        assertThat(view).isEqualTo(new FilingView(filing.id(), "Acme", "10-K", FAILED, SUBMITTED_AT, "boom"));
    }

    private static Filing withHistory(List<FilingStatus> history) {
        Filing filing = newFiling();
        history.forEach(status -> filing.changeStatus(status, "earlier", SUBMITTED_AT.plusSeconds(1)));
        return filing;
    }

    private static Filing newFiling() {
        return Filing.submit(UUID.randomUUID(), "Acme", "10-K", "text", SUBMITTED_AT);
    }
}
