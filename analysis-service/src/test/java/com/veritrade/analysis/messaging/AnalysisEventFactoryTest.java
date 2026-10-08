package com.veritrade.analysis.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.analysis.domain.AnalysisResult;
import com.veritrade.analysis.domain.Finding;
import com.veritrade.analysis.support.ContractFixtures;
import com.veritrade.analysis.support.TestMessages;
import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.AnalysisStartedPayload;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class AnalysisEventFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:02Z");
    private static final String CORRELATION_ID = "c0a8012e-5b1f-4d3c-8e2a-7f6b9d4c1a20";
    private static final int LIMIT = MessagingProperties.SCHEMA_MAX_REASON_LENGTH;
    /** One character outside the Basic Multilingual Plane: two UTF-16 code units. */
    private static final String EMOJI = "\uD83D\uDE00";
    private static final UUID FILING_ID = UUID.fromString("3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12");

    private final AnalysisEventFactory factory = new AnalysisEventFactory(Clock.fixed(NOW, ZoneOffset.UTC), TestMessages.MESSAGING);

    @Test
    void buildsAStartedEventThatMatchesTheSchema() {
        final EventEnvelope<AnalysisStartedPayload> event = factory.started(FILING_ID, CORRELATION_ID);

        assertThat(event.eventId()).isEqualTo(EventIds.forFiling(FILING_ID, EventType.ANALYSIS_STARTED));
        assertThat(event.eventType()).isEqualTo(EventType.ANALYSIS_STARTED);
        assertThat(event.eventVersion()).isEqualTo(EventEnvelope.CURRENT_VERSION);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(event.payload()).isEqualTo(new AnalysisStartedPayload(FILING_ID, NOW));
        assertValid(event);
    }

    @Test
    void buildsTheCompletedEventOfTheContractExample() {
        final String content = ContractFixtures.example(EventType.FILING_SUBMITTED).get("payload").get("content").asString();
        final AnalysisResult result = TestMessages.bundledAnalyzer().analyze(content);

        final EventEnvelope<AnalysisCompletedPayload> event = factory.completed(FILING_ID, CORRELATION_ID, result);

        final JsonNode json = ContractFixtures.MAPPER.valueToTree(event);
        assertThat(json).isEqualTo(ContractFixtures.example(EventType.ANALYSIS_COMPLETED));
        assertValid(event);
    }

    @Test
    void buildsACompletedEventWithoutFindings() {
        final AnalysisResult empty = new AnalysisResult("1.0", List.of(), RiskLevel.NONE, Map.of());

        final EventEnvelope<AnalysisCompletedPayload> event = factory.completed(FILING_ID, CORRELATION_ID, empty);

        assertThat(event.payload().summary().totalFindings()).isZero();
        assertThat(event.payload().summary().overallRiskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(event.payload().summary().byCategory()).isEmpty();
        assertValid(event);
    }

    @Test
    void copiesEveryFindingField() {
        final Finding finding = new Finding(RiskCategory.MARKET, Severity.LOW, "MKT-005", "inflation", "some inflation", 5);
        final AnalysisResult result = new AnalysisResult("1.0", List.of(finding), RiskLevel.LOW, Map.of(RiskCategory.MARKET, 1));

        final AnalysisCompletedPayload payload = factory.completed(FILING_ID, CORRELATION_ID, result).payload();

        assertThat(payload.findings()).singleElement().satisfies(p -> {
            assertThat(p.category()).isEqualTo(RiskCategory.MARKET);
            assertThat(p.severity()).isEqualTo(Severity.LOW);
            assertThat(p.ruleId()).isEqualTo("MKT-005");
            assertThat(p.matchedText()).isEqualTo("inflation");
            assertThat(p.excerpt()).isEqualTo("some inflation");
            assertThat(p.position()).isEqualTo(5);
        });
    }

    @Test
    void buildsTheFailedEventOfTheContractExample() {
        final Clock failedAt = Clock.fixed(Instant.parse("2026-10-07T12:00:05Z"), ZoneOffset.UTC);

        final EventEnvelope<AnalysisFailedPayload> event = new AnalysisEventFactory(failedAt, TestMessages.MESSAGING)
                .failed(FILING_ID, CORRELATION_ID, "Analysis failed after 3 attempts: rule engine error");

        final JsonNode json = ContractFixtures.MAPPER.valueToTree(event);
        assertThat(json).isEqualTo(ContractFixtures.example(EventType.ANALYSIS_FAILED));
        assertValid(event);
    }

    @ParameterizedTest(name = "{0} code units")
    @ValueSource(ints = {LIMIT - 1, LIMIT})
    void keepsAReasonUpToTheLimitUnchanged(final int length) {
        final String reason = "y".repeat(length);

        assertThat(factory.failed(FILING_ID, CORRELATION_ID, reason).payload().reason()).isEqualTo(reason);
    }

    @ParameterizedTest(name = "{0} code units")
    @ValueSource(ints = {LIMIT + 1, 5000})
    void cutsALongerReasonToTheLimit(final int length) {
        final EventEnvelope<AnalysisFailedPayload> event = factory.failed(FILING_ID, CORRELATION_ID, "x".repeat(length));

        assertThat(event.payload().reason()).isEqualTo("x".repeat(LIMIT));
        assertValid(event);
    }

    @Test
    void dropsASurrogatePairThatWouldBeSplitAtTheLimit() {
        final String reason = "x".repeat(LIMIT - 1) + EMOJI + "tail";

        final String cut = factory.failed(FILING_ID, CORRELATION_ID, reason).payload().reason();

        assertThat(cut).isEqualTo("x".repeat(LIMIT - 1));
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }

    @Test
    void keepsASurrogatePairThatEndsExactlyAtTheLimit() {
        final String reason = "x".repeat(LIMIT - 2) + EMOJI + "tail";

        assertThat(factory.failed(FILING_ID, CORRELATION_ID, reason).payload().reason())
                .isEqualTo("x".repeat(LIMIT - 2) + EMOJI)
                .hasSize(LIMIT);
    }

    @Test
    void cutsAReasonMadeOnlyOfSurrogatePairsToWholeCharacters() {
        final String cut = factory.failed(FILING_ID, CORRELATION_ID, EMOJI.repeat(LIMIT)).payload().reason();

        assertThat(cut).hasSize(LIMIT).isEqualTo(EMOJI.repeat(LIMIT / 2));
    }

    @Test
    void appliesAConfiguredLowerLimit() {
        final AnalysisEventFactory shortReasons = new AnalysisEventFactory(Clock.fixed(NOW, ZoneOffset.UTC),
                new MessagingProperties(Duration.ofSeconds(1), 3));

        assertThat(shortReasons.failed(FILING_ID, CORRELATION_ID, "ab" + EMOJI).payload().reason()).isEqualTo("ab");
        assertThat(shortReasons.failed(FILING_ID, CORRELATION_ID, "a" + EMOJI + "b").payload().reason())
                .isEqualTo("a" + EMOJI);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, LIMIT + 1})
    void refusesALimitOutsideTheSchema(final int limit) {
        assertThatThrownBy(() -> new MessagingProperties(Duration.ofSeconds(1), limit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxReasonLength must be between 2 and 1000");
    }

    @Test
    void eventIdsAreTheSameForTheSameFilingAndDifferPerType() {
        final AnalysisResult empty = new AnalysisResult("1.0", List.of(), RiskLevel.NONE, Map.of());

        assertThat(factory.started(FILING_ID, "a").eventId()).isEqualTo(factory.started(FILING_ID, "b").eventId());
        assertThat(List.of(factory.started(FILING_ID, "a").eventId(),
                factory.completed(FILING_ID, "a", empty).eventId(),
                factory.failed(FILING_ID, "a", "r").eventId())).doesNotHaveDuplicates();
    }

    private static void assertValid(final EventEnvelope<?> event) {
        assertThat(ContractFixtures.validate(event.eventType(), (JsonNode) ContractFixtures.MAPPER.valueToTree(event)))
                .isEmpty();
    }
}
