package com.veritrade.ingestion.domain;

import static com.veritrade.ingestion.domain.FilingStatus.ANALYZING;
import static com.veritrade.ingestion.domain.FilingStatus.COMPLETED;
import static com.veritrade.ingestion.domain.FilingStatus.FAILED;
import static com.veritrade.ingestion.domain.FilingStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class FilingStatusTest {

    static Stream<Arguments> everyPair() {
        return Stream.of(
                Arguments.of(SUBMITTED, SUBMITTED, false),
                Arguments.of(SUBMITTED, ANALYZING, true),
                Arguments.of(SUBMITTED, COMPLETED, true),
                Arguments.of(SUBMITTED, FAILED, true),
                Arguments.of(ANALYZING, SUBMITTED, false),
                Arguments.of(ANALYZING, ANALYZING, false),
                Arguments.of(ANALYZING, COMPLETED, true),
                Arguments.of(ANALYZING, FAILED, true),
                Arguments.of(COMPLETED, SUBMITTED, false),
                Arguments.of(COMPLETED, ANALYZING, false),
                Arguments.of(COMPLETED, COMPLETED, false),
                Arguments.of(COMPLETED, FAILED, false),
                Arguments.of(FAILED, SUBMITTED, false),
                Arguments.of(FAILED, ANALYZING, false),
                Arguments.of(FAILED, COMPLETED, false),
                Arguments.of(FAILED, FAILED, false));
    }

    @ParameterizedTest(name = "{0} -> {1} allowed: {2}")
    @MethodSource("everyPair")
    void allowsOnlyForwardMovesBeforeAFinalStatus(FilingStatus from, FilingStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }

    static Stream<Arguments> everyChange() {
        return Stream.of(
                Arguments.of(SUBMITTED, SUBMITTED, StatusChange.DUPLICATE),
                Arguments.of(SUBMITTED, ANALYZING, StatusChange.APPLIED),
                Arguments.of(SUBMITTED, COMPLETED, StatusChange.APPLIED),
                Arguments.of(SUBMITTED, FAILED, StatusChange.APPLIED),
                Arguments.of(ANALYZING, SUBMITTED, StatusChange.REJECTED),
                Arguments.of(ANALYZING, ANALYZING, StatusChange.DUPLICATE),
                Arguments.of(ANALYZING, COMPLETED, StatusChange.APPLIED),
                Arguments.of(ANALYZING, FAILED, StatusChange.APPLIED),
                Arguments.of(COMPLETED, SUBMITTED, StatusChange.REJECTED),
                Arguments.of(COMPLETED, ANALYZING, StatusChange.REJECTED),
                Arguments.of(COMPLETED, COMPLETED, StatusChange.DUPLICATE),
                Arguments.of(COMPLETED, FAILED, StatusChange.REJECTED),
                Arguments.of(FAILED, SUBMITTED, StatusChange.REJECTED),
                Arguments.of(FAILED, ANALYZING, StatusChange.REJECTED),
                Arguments.of(FAILED, COMPLETED, StatusChange.REJECTED),
                Arguments.of(FAILED, FAILED, StatusChange.DUPLICATE));
    }

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @MethodSource("everyChange")
    void classifiesEveryRequestedChange(FilingStatus from, FilingStatus to, StatusChange expected) {
        assertThat(from.transitionTo(to)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(value = FilingStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    void onlyFailedKeepsAFailureReason(FilingStatus status) {
        assertThat(status.failureReasonToKeep("rule engine error")).isNull();
        assertThat(FAILED.failureReasonToKeep("rule engine error")).isEqualTo("rule engine error");
    }

    @ParameterizedTest
    @MethodSource("terminalFlags")
    void onlyCompletedAndFailedAreFinal(FilingStatus status, boolean terminal) {
        assertThat(status.isTerminal()).isEqualTo(terminal);
    }

    static Stream<Arguments> terminalFlags() {
        return Stream.of(
                Arguments.of(SUBMITTED, false),
                Arguments.of(ANALYZING, false),
                Arguments.of(COMPLETED, true),
                Arguments.of(FAILED, true));
    }
}
