package com.veritrade.ingestion.service;

import static com.veritrade.ingestion.domain.FilingStatus.ANALYZING;
import static com.veritrade.ingestion.domain.FilingStatus.COMPLETED;
import static com.veritrade.ingestion.domain.FilingStatus.FAILED;
import static com.veritrade.ingestion.domain.FilingStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.veritrade.ingestion.domain.FilingState;
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
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(OutputCaptureExtension.class)
class FilingStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:03.123456789Z");
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-10-07T12:00:03.123456Z");
    private static final long VERSION = 7L;

    private final FilingRepository filings = mock(FilingRepository.class);
    private final FilingStatusService service = new FilingStatusService(filings, Clock.fixed(NOW, ZoneOffset.UTC));

    static Stream<Arguments> eventsAgainstEveryStatus() {
        return Stream.of(
                Arguments.of(SUBMITTED, ANALYZING, StatusChange.APPLIED),
                Arguments.of(SUBMITTED, COMPLETED, StatusChange.APPLIED),
                Arguments.of(SUBMITTED, FAILED, StatusChange.APPLIED),
                Arguments.of(ANALYZING, ANALYZING, StatusChange.DUPLICATE),
                Arguments.of(ANALYZING, COMPLETED, StatusChange.APPLIED),
                Arguments.of(ANALYZING, FAILED, StatusChange.APPLIED),
                Arguments.of(COMPLETED, ANALYZING, StatusChange.REJECTED),
                Arguments.of(COMPLETED, COMPLETED, StatusChange.DUPLICATE),
                Arguments.of(COMPLETED, FAILED, StatusChange.REJECTED),
                Arguments.of(FAILED, ANALYZING, StatusChange.REJECTED),
                Arguments.of(FAILED, COMPLETED, StatusChange.REJECTED),
                Arguments.of(FAILED, FAILED, StatusChange.DUPLICATE));
    }

    @ParameterizedTest(name = "{0} + event for {1} -> {2}")
    @MethodSource("eventsAgainstEveryStatus")
    void writesOnlyAllowedTransitionsAndNeverThrows(final FilingStatus current, final FilingStatus target, final StatusChange expected) {
        final UUID filingId = filingIn(current);

        final StatusChange change = service.apply(update(filingId, target, "reason"));

        assertThat(change).isEqualTo(expected);
        if (expected == StatusChange.APPLIED) {
            verify(filings).changeStatus(eq(filingId), eq(VERSION), eq(target), any(), eq(NOW_IN_MICROS));
        } else {
            verify(filings, never()).changeStatus(any(), any(), any(), any(), any());
        }
    }

    /** Review W3-07: a status event must not load the filing entity, whose content can be 2 MB. */
    @Test
    void neverLoadsTheFilingEntity() {
        final UUID filingId = filingIn(SUBMITTED);

        service.apply(update(filingId, ANALYZING, null));

        verify(filings).findStateById(filingId);
        verify(filings, never()).findById(any());
        verify(filings, never()).getReferenceById(any());
        verify(filings, never()).save(any());
    }

    @Test
    void lateStartedAfterCompletedIsIgnoredWithAWarning(final CapturedOutput output) {
        final UUID filingId = filingIn(COMPLETED);
        final StatusUpdate late = update(filingId, ANALYZING, null);

        assertThat(service.apply(late)).isEqualTo(StatusChange.REJECTED);

        assertThat(output).contains("WARN", "Late or contradictory event " + late.eventId(),
                "filing " + filingId + " is COMPLETED, event requests ANALYZING");
    }

    @Test
    void failedAfterCompletedIsIgnoredWithAWarning(final CapturedOutput output) {
        final UUID filingId = filingIn(COMPLETED);
        final StatusUpdate contradictory = update(filingId, FAILED, "rule engine error");

        assertThat(service.apply(contradictory)).isEqualTo(StatusChange.REJECTED);

        verify(filings, never()).changeStatus(any(), any(), any(), any(), any());
        assertThat(output).contains("WARN", contradictory.eventId().toString(), "is COMPLETED, event requests FAILED");
    }

    @Test
    void duplicateEventIsNotAWarning(final CapturedOutput output) {
        final UUID filingId = filingIn(COMPLETED);

        service.apply(update(filingId, COMPLETED, null));

        assertThat(output).doesNotContain("WARN " + FilingStatusService.class.getName()).contains("is already COMPLETED");
    }

    @Test
    void storesTheFailureReasonAndTheTimeOfTheChange() {
        final UUID filingId = filingIn(ANALYZING);

        service.apply(update(filingId, FAILED, "Analysis failed after 3 attempts"));

        verify(filings).changeStatus(filingId, VERSION, FAILED, "Analysis failed after 3 attempts", NOW_IN_MICROS);
    }

    @Test
    void keepsAFailureReasonOfExactlyTheLimitWhole() {
        final UUID filingId = filingIn(SUBMITTED);
        final String reason = "r".repeat(TestProperties.MAX_FAILURE_REASON_LENGTH);

        service.apply(update(filingId, FAILED, reason));

        verify(filings).changeStatus(filingId, VERSION, FAILED, reason, NOW_IN_MICROS);
    }

    @Test
    void storesNoReasonForAnyOtherStatus() {
        final UUID filingId = filingIn(ANALYZING);

        service.apply(update(filingId, COMPLETED, "ignored"));

        verify(filings).changeStatus(filingId, VERSION, COMPLETED, null, NOW_IN_MICROS);
    }

    @Test
    void failsWhenTheFilingChangedConcurrentlySoTheListenerRetries() {
        final UUID filingId = filingIn(SUBMITTED);
        when(filings.changeStatus(any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.apply(update(filingId, ANALYZING, null)))
                .isInstanceOf(OptimisticLockingFailureException.class).hasMessageContaining(filingId.toString());
    }

    @Test
    void failsForAnUnknownFiling() {
        final UUID unknown = UUID.randomUUID();
        when(filings.findStateById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(update(unknown, ANALYZING, null)))
                .isInstanceOfSatisfying(FilingNotFoundException.class, e -> assertThat(e.filingId()).isEqualTo(unknown));
    }

    @Test
    void statusUpdateRequiresIdsAndTarget() {
        final UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new StatusUpdate(null, id, ANALYZING, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatusUpdate(id, null, ANALYZING, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatusUpdate(id, id, null, null)).isInstanceOf(NullPointerException.class);
    }

    private UUID filingIn(final FilingStatus status) {
        final UUID filingId = UUID.randomUUID();
        when(filings.findStateById(filingId)).thenReturn(Optional.of(new FilingState(filingId, status, VERSION)));
        when(filings.changeStatus(eq(filingId), any(), any(), any(), any())).thenReturn(1);
        return filingId;
    }

    private static StatusUpdate update(final UUID filingId, final FilingStatus target, final String reason) {
        return new StatusUpdate(UUID.randomUUID(), filingId, target, reason);
    }
}
