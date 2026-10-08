package com.veritrade.reporting.messaging;

import static com.veritrade.reporting.support.TestEvents.COMPLETED_EXAMPLE;
import static com.veritrade.reporting.support.TestEvents.FAILED_EXAMPLE;
import static com.veritrade.reporting.support.TestEvents.example;
import static com.veritrade.reporting.support.TestEvents.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.AnalysisFailedPayload;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.contracts.model.RiskCategory;
import com.veritrade.contracts.model.RiskLevel;
import com.veritrade.contracts.model.Severity;
import com.veritrade.reporting.support.TestEvents;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import tools.jackson.databind.node.ObjectNode;

class AnalysisEventReaderTest {

    private static final String RK_COMPLETED = MessagingTopology.RK_ANALYSIS_COMPLETED;
    private static final String RK_FAILED = MessagingTopology.RK_ANALYSIS_FAILED;

    private final AnalysisEventReader reader =
            new AnalysisEventReader(TestEvents.MAPPER, new AnalysisEventValidator(TestEvents.LIMITS));

    @Test
    void readsTheCompletedContractExample() {
        EventEnvelope<?> envelope = reader.read(message(example(COMPLETED_EXAMPLE), RK_COMPLETED));

        assertThat(envelope.eventType()).isEqualTo(EventType.ANALYSIS_COMPLETED);
        assertThat(envelope.eventId()).isEqualTo(UUID.fromString("6c3c4f56-06d6-393c-96cc-67ed6a106eca"));
        AnalysisCompletedPayload payload = (AnalysisCompletedPayload) envelope.payload();
        assertThat(payload.filingId()).isEqualTo(UUID.fromString("3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12"));
        assertThat(payload.findings()).hasSize(3);
        assertThat(payload.summary().overallRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void readsTheFailedContractExample() {
        EventEnvelope<?> envelope = reader.read(message(example(FAILED_EXAMPLE), RK_FAILED));

        assertThat(envelope.payload()).isInstanceOfSatisfying(AnalysisFailedPayload.class,
                failed -> assertThat(failed.reason()).isEqualTo("Analysis failed after 3 attempts: rule engine error"));
    }

    @Test
    void ignoresUnknownFieldsEverywhere() {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        event.put("addedLater", "x");
        payload(event).put("newPayloadField", 1);
        ((ObjectNode) payload(event).get("summary")).put("riskScore", 7.5);
        ((ObjectNode) payload(event).get("findings").get(0)).putObject("details").put("nested", true);

        assertThat(reader.read(message(event, RK_COMPLETED)).payload()).isInstanceOf(AnalysisCompletedPayload.class);
    }

    @Test
    void dispatchesOnEventTypeAndNotOnTheRoutingKeyOrAJavaTypeHeader() {
        Message message = message(example(FAILED_EXAMPLE), RK_COMPLETED);
        message.getMessageProperties().setHeader("__TypeId__", AnalysisCompletedPayload.class.getName());

        assertThat(reader.read(message).eventType()).isEqualTo(EventType.ANALYSIS_FAILED);
    }

    @Test
    void fallsBackToTheRoutingKeyWhenEventTypeIsAbsent() {
        ObjectNode event = example(FAILED_EXAMPLE);
        event.remove("eventType");

        assertThat(reader.read(message(event, RK_FAILED)).eventType()).isEqualTo(EventType.ANALYSIS_FAILED);
    }

    @Test
    void rejectsAMessageWithoutEventTypeAndWithAnUnknownRoutingKey() {
        ObjectNode event = example(FAILED_EXAMPLE);
        event.remove("eventType");

        assertInvalid(message(event, "something.else"));
        assertInvalid(message(event, null));
    }

    @Test
    void rejectsAnUnknownEventType() {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        event.put("eventType", "ANALYSIS_EXPLODED");

        assertInvalid(message(event, RK_COMPLETED));
    }

    @ParameterizedTest
    @EnumSource(value = EventType.class, names = {"FILING_SUBMITTED", "ANALYSIS_STARTED"})
    void rejectsEventTypesThatReportingDoesNotConsume(EventType type) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        event.put("eventType", type.name());

        assertInvalid(message(event, type.routingKey()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "", "[1,2,3]", "\"text\"", "42", "null", "{\"eventType\":"})
    void rejectsMalformedOrNonObjectJson(String body) {
        assertInvalid(message(body.getBytes(StandardCharsets.UTF_8), RK_COMPLETED));
    }

    @Test
    void rejectsABodyThatIsNotUtf8Json() {
        assertInvalid(message(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 1}, RK_COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "eventVersion", "occurredAt", "correlationId", "payload"})
    void rejectsAMissingEnvelopeField(String field) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        event.remove(field);

        assertInvalid(message(event, RK_COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"filingId", "analyzedAt", "rulesVersion", "summary", "findings"})
    void rejectsAMissingCompletedPayloadField(String field) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        payload(event).remove(field);

        assertInvalid(message(event, RK_COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"filingId", "analyzedAt", "rulesVersion", "summary"})
    void rejectsANullCompletedPayloadField(String field) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        payload(event).putNull(field);

        assertInvalid(message(event, RK_COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"totalFindings", "overallRiskLevel", "byCategory"})
    void rejectsAMissingSummaryField(String field) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("summary")).remove(field);

        assertInvalid(message(event, RK_COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"category", "severity", "ruleId", "matchedText", "excerpt", "position"})
    void rejectsAMissingFindingField(String field) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("findings").get(1)).remove(field);

        assertInvalid(message(event, RK_COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"category", "severity", "ruleId", "matchedText", "excerpt", "position"})
    void rejectsANullFindingField(String field) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("findings").get(1)).putNull(field);

        assertInvalid(message(event, RK_COMPLETED));
    }

    @Test
    void rejectsANullFindingAndInvalidValues() {
        ObjectNode nullFinding = example(COMPLETED_EXAMPLE);
        payload(nullFinding).withArray("findings").addNull();
        ObjectNode negativePosition = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(negativePosition).get("findings").get(0)).put("position", -1);
        ObjectNode negativeTotal = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(negativeTotal).get("summary")).put("totalFindings", -1);
        ObjectNode emptyMatch = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(emptyMatch).get("findings").get(0)).put("matchedText", "");

        assertInvalid(message(nullFinding, RK_COMPLETED));
        assertInvalid(message(negativePosition, RK_COMPLETED));
        assertInvalid(message(negativeTotal, RK_COMPLETED));
        assertInvalid(message(emptyMatch, RK_COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"severity", "category"})
    void rejectsAnUnknownEnumValue(String field) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("findings").get(0)).put(field, "EXTREME");

        assertInvalid(message(event, RK_COMPLETED));
    }

    @Test
    void rejectsAnUnknownRiskLevel() {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("summary")).put("overallRiskLevel", "SEVERE");

        assertInvalid(message(event, RK_COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"filingId", "failedAt", "reason"})
    void rejectsAMissingFailedPayloadField(String field) {
        ObjectNode event = example(FAILED_EXAMPLE);
        payload(event).remove(field);

        assertInvalid(message(event, RK_FAILED));
    }

    @Test
    void rejectsABlankFailureReasonAndAMalformedFilingId() {
        ObjectNode blankReason = example(FAILED_EXAMPLE);
        payload(blankReason).put("reason", "");
        ObjectNode badId = example(FAILED_EXAMPLE);
        payload(badId).put("filingId", "not-a-uuid");

        assertInvalid(message(blankReason, RK_FAILED));
        assertInvalid(message(badId, RK_FAILED));
    }

    @Test
    void rejectsAVersionNewerThanTheOneItUnderstands() {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        event.put("eventVersion", EventEnvelope.CURRENT_VERSION + 1);
        ObjectNode zero = example(COMPLETED_EXAMPLE);
        zero.put("eventVersion", 0);

        assertInvalid(message(event, RK_COMPLETED));
        assertInvalid(message(zero, RK_COMPLETED));
    }

    @ParameterizedTest
    @EnumSource(RiskLevel.class)
    void acceptsEveryRiskLevel(RiskLevel level) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("summary")).put("overallRiskLevel", level.name());

        AnalysisCompletedPayload payload = (AnalysisCompletedPayload) reader.read(message(event, RK_COMPLETED)).payload();

        assertThat(payload.summary().overallRiskLevel()).isEqualTo(level);
    }

    @ParameterizedTest
    @EnumSource(Severity.class)
    void acceptsEverySeverity(Severity severity) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("findings").get(0)).put("severity", severity.name());

        AnalysisCompletedPayload payload = (AnalysisCompletedPayload) reader.read(message(event, RK_COMPLETED)).payload();

        assertThat(payload.findings().getFirst().severity()).isEqualTo(severity);
    }

    @ParameterizedTest
    @EnumSource(RiskCategory.class)
    void acceptsEveryCategory(RiskCategory category) {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("findings").get(0)).put("category", category.name());

        AnalysisCompletedPayload payload = (AnalysisCompletedPayload) reader.read(message(event, RK_COMPLETED)).payload();

        assertThat(payload.findings().getFirst().category()).isEqualTo(category);
    }

    @Test
    void acceptsZeroFindingsWithLevelNone() {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        payload(event).putArray("findings");
        ObjectNode summary = (ObjectNode) payload(event).get("summary");
        summary.put("totalFindings", 0).put("overallRiskLevel", "NONE").putObject("byCategory");

        AnalysisCompletedPayload payload = (AnalysisCompletedPayload) reader.read(message(event, RK_COMPLETED)).payload();

        assertThat(payload.findings()).isEmpty();
        assertThat(payload.summary().overallRiskLevel()).isEqualTo(RiskLevel.NONE);
    }

    @Test
    void acceptsTextExactlyAtTheContractLimits() {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ObjectNode finding = (ObjectNode) payload(event).get("findings").get(0);
        finding.put("matchedText", "m".repeat(500)).put("excerpt", "e".repeat(1000)).put("ruleId", "R".repeat(32));
        payload(event).put("rulesVersion", "v".repeat(32));

        assertThat(reader.read(message(event, RK_COMPLETED)).payload()).isInstanceOf(AnalysisCompletedPayload.class);
    }

    @Test
    void acceptsEmojiTextExactlyAtTheLimitsInUtf16Units() {
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("findings").get(0))
                .put("matchedText", TestEvents.emoji(250)).put("excerpt", TestEvents.emoji(500));
        ObjectNode failed = example(FAILED_EXAMPLE);
        payload(failed).put("reason", TestEvents.emoji(500));

        assertThat(reader.read(message(event, RK_COMPLETED)).payload()).isInstanceOf(AnalysisCompletedPayload.class);
        assertThat(reader.read(message(failed, RK_FAILED)).payload()).isInstanceOf(AnalysisFailedPayload.class);
    }

    /** The contract counts maxLength in UTF-16 units: these values are within the limit in code points only. */
    @ParameterizedTest
    @ValueSource(strings = {"matchedText:251", "excerpt:501"})
    void rejectsEmojiTextOverTheLimitInUtf16UnitsAlthoughWithinItInCodePoints(String fieldAndEmoji) {
        String[] parts = fieldAndEmoji.split(":");
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("findings").get(0)).put(parts[0], TestEvents.emoji(Integer.parseInt(parts[1])));
        ObjectNode failed = example(FAILED_EXAMPLE);
        payload(failed).put("reason", TestEvents.emoji(500) + "x");

        assertInvalid(message(event, RK_COMPLETED));
        assertInvalid(message(failed, RK_FAILED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"matchedText:501", "excerpt:1001", "ruleId:33"})
    void rejectsFindingTextOverTheContractLimit(String fieldAndLength) {
        String[] parts = fieldAndLength.split(":");
        ObjectNode event = example(COMPLETED_EXAMPLE);
        ((ObjectNode) payload(event).get("findings").get(0)).put(parts[0], "x".repeat(Integer.parseInt(parts[1])));

        assertInvalid(message(event, RK_COMPLETED));
    }

    @Test
    void rejectsRulesVersionAndReasonOverTheContractLimit() {
        ObjectNode completed = example(COMPLETED_EXAMPLE);
        payload(completed).put("rulesVersion", "v".repeat(33));
        ObjectNode failed = example(FAILED_EXAMPLE);
        payload(failed).put("reason", TestEvents.emoji(1001));

        assertInvalid(message(completed, RK_COMPLETED));
        assertInvalid(message(failed, RK_FAILED));
    }

    private static ObjectNode payload(ObjectNode event) {
        return (ObjectNode) event.get("payload");
    }

    private void assertInvalid(Message message) {
        assertThatThrownBy(() -> reader.read(message))
                .isInstanceOf(InvalidEventException.class)
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}
