package com.veritrade.ingestion.service;

import static com.veritrade.ingestion.domain.FilingStatus.ANALYZING;
import static com.veritrade.ingestion.domain.FilingStatus.COMPLETED;
import static com.veritrade.ingestion.domain.FilingStatus.FAILED;
import static com.veritrade.ingestion.domain.FilingStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.veritrade.ingestion.domain.Filing;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.domain.StatusChange;
import com.veritrade.ingestion.repository.FilingRepository;
import com.veritrade.ingestion.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class FilingStatusServiceTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:03.123456789Z");
    private static final String EMOJI = "😀";

    private final FilingRepository filings = mock(FilingRepository.class);
    private final FilingStatusService service =
            new FilingStatusService(filings, Clock.fixed(NOW, ZoneOffset.UTC), TestProperties.defaults());

    static Stream<Arguments> eventsAgainstEveryStatus() {
        return Stream.of(
                Arguments.of(SUBMITTED, ANALYZING, StatusChange.APPLIED, ANALYZING),
                Arguments.of(SUBMITTED, COMPLETED, StatusChange.APPLIED, COMPLETED),
                Arguments.of(SUBMITTED, FAILED, StatusChange.APPLIED, FAILED),
                Arguments.of(ANALYZING, ANALYZING, StatusChange.DUPLICATE, ANALYZING),
                Arguments.of(ANALYZING, COMPLETED, StatusChange.APPLIED, COMPLETED),
                Arguments.of(ANALYZING, FAILED, StatusChange.APPLIED, FAILED),
                Arguments.of(COMPLETED, ANALYZING, StatusChange.REJECTED, COMPLETED),
                Arguments.of(COMPLETED, COMPLETED, StatusChange.DUPLICATE, COMPLETED),
                Arguments.of(COMPLETED, FAILED, StatusChange.REJECTED, COMPLETED),
                Arguments.of(FAILED, ANALYZING, StatusChange.REJECTED, FAILED),
                Arguments.of(FAILED, COMPLETED, StatusChange.REJECTED, FAILED),
                Arguments.of(FAILED, FAILED, StatusChange.DUPLICATE, FAILED));
    }

    @ParameterizedTest(name = "{0} + event for {1} -> {2}")
    @MethodSource("eventsAgainstEveryStatus")
    void appliesOnlyAllowedTransitionsAndNeverThrows(
            FilingStatus current, FilingStatus target, StatusChange expected, FilingStatus after) {
        Filing filing = filingIn(current);

        StatusChange change = service.apply(update(filing.id(), target, "reason"));

        assertThat(change).isEqualTo(expected);
        assertThat(filing.status()).isEqualTo(after);
    }

    @Test
    void lateStartedAfterCompletedIsIgnoredWithAWarning(CapturedOutput output) {
        Filing filing = filingIn(COMPLETED);
        StatusUpdate late = update(filing.id(), ANALYZING, null);

        assertThat(service.apply(late)).isEqualTo(StatusChange.REJECTED);

        assertThat(output).contains("WARN", "Late or contradictory event " + late.eventId(),
                "filing " + filing.id() + " is COMPLETED, event requests ANALYZING");
    }

    @Test
    void failedAfterCompletedIsIgnoredWithAWarningAndKeepsNoReason(CapturedOutput output) {
        Filing filing = filingIn(COMPLETED);
        StatusUpdate contradictory = update(filing.id(), FAILED, "rule engine error");

        assertThat(service.apply(contradictory)).isEqualTo(StatusChange.REJECTED);

        assertThat(filing.failureReason()).isNull();
        assertThat(output).contains("WARN", contradictory.eventId().toString(), "is COMPLETED, event requests FAILED");
    }

    @Test
    void completedAfterFailedIsIgnoredAndKeepsTheFirstReason(CapturedOutput output) {
        Filing filing = filingIn(FAILED);

        assertThat(service.apply(update(filing.id(), COMPLETED, null))).isEqualTo(StatusChange.REJECTED);

        assertThat(filing.failureReason()).isEqualTo("earlier");
        assertThat(output).contains("is FAILED, event requests COMPLETED");
    }

    @Test
    void duplicateEventIsNotAWarning(CapturedOutput output) {
        Filing filing = filingIn(COMPLETED);

        service.apply(update(filing.id(), COMPLETED, null));

        assertThat(output).doesNotContain("WARN").contains("is already COMPLETED");
    }

    @Test
    void storesTheFailureReasonAndTheTimeOfTheChange() {
        Filing filing = filingIn(ANALYZING);

        service.apply(update(filing.id(), FAILED, "Analysis failed after 3 attempts"));

        assertThat(filing.failureReason()).isEqualTo("Analysis failed after 3 attempts");
        assertThat(filing.updatedAt()).isEqualTo(Instant.parse("2026-10-07T12:00:03.123456Z"));
    }

    @Test
    void keepsAFailureReasonOfExactlyTheLimit() {
        Filing filing = filingIn(SUBMITTED);
        String reason = "r".repeat(TestProperties.MAX_FAILURE_REASON_LENGTH);

        service.apply(update(filing.id(), FAILED, reason));

        assertThat(filing.failureReason()).isEqualTo(reason);
    }

    @Test
    void shortensATooLongFailureReasonToTheColumnLength() {
        Filing filing = filingIn(SUBMITTED);

        service.apply(update(filing.id(), FAILED, EMOJI.repeat(TestProperties.MAX_FAILURE_REASON_LENGTH)));

        assertThat(filing.failureReason()).isEqualTo(EMOJI.repeat(TestProperties.MAX_FAILURE_REASON_LENGTH / 2));
    }

    @Test
    void shortensWithoutSplittingASurrogatePair() {
        Filing filing = filingIn(SUBMITTED);

        service.apply(update(filing.id(), FAILED, "a" + EMOJI.repeat(TestProperties.MAX_FAILURE_REASON_LENGTH)));

        String stored = filing.failureReason();
        assertThat(stored).hasSize(TestProperties.MAX_FAILURE_REASON_LENGTH - 1);
        assertThat(stored).isEqualTo("a" + EMOJI.repeat(TestProperties.MAX_FAILURE_REASON_LENGTH / 2 - 1));
    }

    @Test
    void failsForAnUnknownFiling() {
        UUID unknown = UUID.randomUUID();
        when(filings.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(update(unknown, ANALYZING, null)))
                .isInstanceOfSatisfying(FilingNotFoundException.class, e -> assertThat(e.filingId()).isEqualTo(unknown));
    }

    @Test
    void statusUpdateRequiresIdsAndTarget() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new StatusUpdate(null, id, ANALYZING, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatusUpdate(id, null, ANALYZING, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatusUpdate(id, id, null, null)).isInstanceOf(NullPointerException.class);
    }

    private Filing filingIn(FilingStatus status) {
        Filing filing = Filing.submit(UUID.randomUUID(), "Acme", "10-K", "text", SUBMITTED_AT);
        if (status == FAILED) {
            filing.changeStatus(FAILED, "earlier", SUBMITTED_AT);
        } else if (status != SUBMITTED) {
            filing.changeStatus(status, null, SUBMITTED_AT);
        }
        when(filings.findById(filing.id())).thenReturn(Optional.of(filing));
        return filing;
    }

    private static StatusUpdate update(UUID filingId, FilingStatus target, String reason) {
        return new StatusUpdate(UUID.randomUUID(), filingId, target, reason);
    }
}
