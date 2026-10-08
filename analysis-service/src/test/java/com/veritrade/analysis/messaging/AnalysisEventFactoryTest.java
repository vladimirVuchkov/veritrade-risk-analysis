package com.veritrade.analysis.messaging;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AnalysisEventFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:02Z");
    private static final String CORRELATION_ID = "c0a8012e-5b1f-4d3c-8e2a-7f6b9d4c1a20";
    private static final UUID FILING_ID = UUID.fromString("3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12");

    private final AnalysisEventFactory factory = new AnalysisEventFactory(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void buildsAStartedEventThatMatchesTheSchema() {
        EventEnvelope<AnalysisStartedPayload> event = factory.started(FILING_ID, CORRELATION_ID);

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
        String content = ContractFixtures.example(EventType.FILING_SUBMITTED).get("payload").get("content").asString();
        AnalysisResult result = TestMessages.bundledAnalyzer().analyze(content);

        EventEnvelope<AnalysisCompletedPayload> event = factory.completed(FILING_ID, CORRELATION_ID, result);

        JsonNode json = ContractFixtures.MAPPER.valueToTree(event);
        assertThat(json).isEqualTo(ContractFixtures.example(EventType.ANALYSIS_COMPLETED));
        assertValid(event);
    }

    @Test
    void buildsACompletedEventWithoutFindings() {
        AnalysisResult empty = new AnalysisResult("1.0", List.of(), RiskLevel.NONE, Map.of());

        EventEnvelope<AnalysisCompletedPayload> event = factory.completed(FILING_ID, CORRELATION_ID, empty);

        assertThat(event.payload().summary().totalFindings()).isZero();
        assertThat(event.payload().summary().overallRiskLevel()).isEqualTo(RiskLevel.NONE);
        assertThat(event.payload().summary().byCategory()).isEmpty();
        assertValid(event);
    }

    @Test
    void copiesEveryFindingField() {
        Finding finding = new Finding(RiskCategory.MARKET, Severity.LOW, "MKT-005", "inflation", "some inflation", 5);
        AnalysisResult result = new AnalysisResult("1.0", List.of(finding), RiskLevel.LOW, Map.of(RiskCategory.MARKET, 1));

        AnalysisCompletedPayload payload = factory.completed(FILING_ID, CORRELATION_ID, result).payload();

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
        Clock failedAt = Clock.fixed(Instant.parse("2026-10-07T12:00:05Z"), ZoneOffset.UTC);

        EventEnvelope<AnalysisFailedPayload> event = new AnalysisEventFactory(failedAt)
                .failed(FILING_ID, CORRELATION_ID, "Analysis failed after 3 attempts: rule engine error");

        JsonNode json = ContractFixtures.MAPPER.valueToTree(event);
        assertThat(json).isEqualTo(ContractFixtures.example(EventType.ANALYSIS_FAILED));
        assertValid(event);
    }

    @Test
    void cutsAFailureReasonToTheSchemaLimit() {
        EventEnvelope<AnalysisFailedPayload> event = factory.failed(FILING_ID, CORRELATION_ID, "x".repeat(5000));

        assertThat(event.payload().reason()).hasSize(AnalysisEventFactory.MAX_REASON_LENGTH);
        assertValid(event);
    }

    @Test
    void keepsAReasonAtTheLimitUnchanged() {
        String reason = "y".repeat(AnalysisEventFactory.MAX_REASON_LENGTH);

        assertThat(factory.failed(FILING_ID, CORRELATION_ID, reason).payload().reason()).isEqualTo(reason);
    }

    @Test
    void eventIdsAreTheSameForTheSameFilingAndDifferPerType() {
        AnalysisResult empty = new AnalysisResult("1.0", List.of(), RiskLevel.NONE, Map.of());

        assertThat(factory.started(FILING_ID, "a").eventId()).isEqualTo(factory.started(FILING_ID, "b").eventId());
        assertThat(List.of(factory.started(FILING_ID, "a").eventId(),
                factory.completed(FILING_ID, "a", empty).eventId(),
                factory.failed(FILING_ID, "a", "r").eventId())).doesNotHaveDuplicates();
    }

    private static void assertValid(EventEnvelope<?> event) {
        assertThat(ContractFixtures.validate(event.eventType(), (JsonNode) ContractFixtures.MAPPER.valueToTree(event)))
                .isEmpty();
    }
}
