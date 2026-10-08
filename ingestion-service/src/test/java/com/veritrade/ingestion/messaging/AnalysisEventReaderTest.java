package com.veritrade.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.ingestion.domain.FilingStatus;
import com.veritrade.ingestion.service.StatusUpdate;
import com.veritrade.ingestion.support.Contracts;
import com.veritrade.ingestion.support.TestProperties;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AnalysisEventReaderTest {

    private static final UUID FILING_ID = UUID.fromString("3f2b8c1e-6a4d-4e2f-9b7a-1c5d8e9f0a12");
    private static final String EMOJI = "\uD83D\uDCC8";
    private static final String CORRELATION_ID = "c0a8012e-5b1f-4d3c-8e2a-7f6b9d4c1a20";

    private final AnalysisEventReader reader = new AnalysisEventReader(JsonMapper.builder().build(), TestProperties.defaults());

    @Test
    void readsTheStartedExample() {
        final AnalysisEvent event = read(Contracts.example(EventType.ANALYSIS_STARTED));

        assertThat(event.eventType()).isEqualTo(EventType.ANALYSIS_STARTED);
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(event.statusUpdate()).isEqualTo(new StatusUpdate(
                UUID.fromString("1247b44c-49ca-375a-a817-c55086f43982"), FILING_ID, FilingStatus.ANALYZING, null));
    }

    @Test
    void readsTheCompletedExample() {
        final AnalysisEvent event = read(Contracts.example(EventType.ANALYSIS_COMPLETED));

        assertThat(event.statusUpdate()).isEqualTo(new StatusUpdate(
                UUID.fromString("6c3c4f56-06d6-393c-96cc-67ed6a106eca"), FILING_ID, FilingStatus.COMPLETED, null));
    }

    @Test
    void readsTheFailedExampleWithItsReason() {
        final AnalysisEvent event = read(Contracts.example(EventType.ANALYSIS_FAILED));

        assertThat(event.statusUpdate().target()).isEqualTo(FilingStatus.FAILED);
        assertThat(event.statusUpdate().failureReason()).isEqualTo("Analysis failed after 3 attempts: rule engine error");
    }

    @ParameterizedTest
    @EnumSource(value = EventType.class, names = "FILING_SUBMITTED", mode = EnumSource.Mode.EXCLUDE)
    void ignoresUnknownFieldsInTheEnvelopeAndThePayload(final EventType type) {
        final ObjectNode event = Contracts.example(type);
        event.put("addedInNewerVersion", "x");
        event.putObject("nested").put("deep", 1);
        ((ObjectNode) event.get("payload")).put("anotherNewField", 42);

        assertThat(read(event).eventType()).isEqualTo(type);
    }

    @Test
    void acceptsTheCurrentEventVersion() {
        final ObjectNode event = Contracts.example(EventType.ANALYSIS_STARTED);
        event.put("eventVersion", EventEnvelope.CURRENT_VERSION);

        assertThat(read(event).eventType()).isEqualTo(EventType.ANALYSIS_STARTED);
    }

    /** Contract, "Event versioning": a newer version goes to the dead-letter queue, it is not guessed at. */
    @ParameterizedTest
    @EnumSource(value = EventType.class, names = "FILING_SUBMITTED", mode = EnumSource.Mode.EXCLUDE)
    void rejectsANewerEventVersionOfEveryAnalysisEvent(final EventType type) {
        assertInvalid(modified(type, e -> e.put("eventVersion", EventEnvelope.CURRENT_VERSION + 1)),
                "Unsupported eventVersion 2");
    }

    @Test
    void rejectsAnEventVersionBeyondTheIntegerRange() {
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.put("eventVersion", new BigInteger("100000000000000000000"))),
                "Unsupported eventVersion");
    }

    @Test
    void rejectsANewerEventVersionEvenWhenThePayloadIsOtherwiseValid() {
        assertInvalid(modified(EventType.ANALYSIS_FAILED, e -> e.put("eventVersion", Integer.MAX_VALUE)),
                "highest supported version is 1");
    }

    @Test
    void ignoresAJavaTypeHintAndDispatchesOnEventType() {
        final ObjectNode event = Contracts.example(EventType.ANALYSIS_COMPLETED);
        event.put("@class", "com.veritrade.contracts.event.AnalysisFailedPayload");

        assertThat(read(event).statusUpdate().target()).isEqualTo(FilingStatus.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{", "{\"eventType\":", "\u0000\u0001", "<xml/>"})
    void rejectsMalformedJson(final String body) {
        assertInvalid(body.getBytes(StandardCharsets.UTF_8), "not valid JSON");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[1,2]", "\"text\"", "42", "null", "true"})
    void rejectsJsonThatIsNotAnObject(final String body) {
        assertInvalid(body.getBytes(StandardCharsets.UTF_8), "not a JSON object");
    }

    @Test
    void rejectsAnEmptyBody() {
        assertThatThrownBy(() -> reader.read(new byte[0])).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsAMissingEventType() {
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.remove("eventType")), "eventType is missing");
    }

    @Test
    void rejectsAnEventTypeThatIsNotAString() {
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.put("eventType", 3)), "eventType is missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "analysis_started", "ANALYSIS_STARTED ", "", "FILING_SUBMITTED"})
    void rejectsUnknownOrNonAnalysisEventTypes(final String eventType) {
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.put("eventType", eventType)), "Not an analysis event type");
    }

    @Test
    void rejectsAMissingPayload() {
        assertInvalid(modified(EventType.ANALYSIS_COMPLETED, e -> e.remove("payload")), "Invalid ANALYSIS_COMPLETED event");
    }

    @Test
    void rejectsAPayloadThatIsNotAnObject() {
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.put("payload", "text")), "Invalid ANALYSIS_STARTED event");
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "correlationId", "occurredAt"})
    void rejectsAMissingRequiredEnvelopeField(final String field) {
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.remove(field)), "Invalid ANALYSIS_STARTED event");
    }

    @Test
    void rejectsAMissingOrInvalidEventVersion() {
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.remove("eventVersion")), "Invalid");
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.put("eventVersion", 0)), "Invalid");
    }

    @Test
    void rejectsAMalformedEventIdOrTimestamp() {
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.put("eventId", "not-a-uuid")), "Invalid");
        assertInvalid(modified(EventType.ANALYSIS_STARTED, e -> e.put("occurredAt", "yesterday")), "Invalid");
    }

    @ParameterizedTest
    @EnumSource(value = EventType.class, names = "FILING_SUBMITTED", mode = EnumSource.Mode.EXCLUDE)
    void rejectsAMissingFilingId(final EventType type) {
        assertInvalid(modified(type, e -> ((ObjectNode) e.get("payload")).remove("filingId")), "filingId is missing");
    }

    @Test
    void rejectsAMalformedFilingId() {
        assertInvalid(modified(EventType.ANALYSIS_STARTED,
                e -> ((ObjectNode) e.get("payload")).put("filingId", "123")), "Invalid ANALYSIS_STARTED event");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void rejectsABlankFailureReason(final String reason) {
        assertInvalid(modified(EventType.ANALYSIS_FAILED,
                e -> ((ObjectNode) e.get("payload")).put("reason", reason)), "reason is missing");
    }

    @Test
    void acceptsAFailureReasonOfExactlyTheLimit() {
        final String reason = "r".repeat(TestProperties.MAX_FAILURE_REASON_LENGTH);

        assertThat(read(failedWith(reason)).statusUpdate().failureReason()).isEqualTo(reason);
    }

    @Test
    void acceptsASurrogatePairThatEndsExactlyAtTheLimit() {
        final String reason = "r".repeat(TestProperties.MAX_FAILURE_REASON_LENGTH - EMOJI.length()) + EMOJI;

        assertThat(read(failedWith(reason)).statusUpdate().failureReason()).isEqualTo(reason);
    }

    /** Contract, "Text limits": the limit counts UTF-16 units, and an over-limit text is not cut but rejected. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 1000})
    void rejectsAFailureReasonOverTheLimitInUtf16Units(final int over) {
        final String reason = "r".repeat(TestProperties.MAX_FAILURE_REASON_LENGTH + over);

        assertThatThrownBy(() -> read(failedWith(reason))).isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("payload.reason has " + reason.length() + " UTF-16 units; the limit is 1000");
    }

    @Test
    void rejectsAFailureReasonOverTheLimitOnlyBecauseOfASurrogatePair() {
        final String reason = "r".repeat(TestProperties.MAX_FAILURE_REASON_LENGTH - 1) + EMOJI;

        assertThat(reason.codePointCount(0, reason.length())).isEqualTo(TestProperties.MAX_FAILURE_REASON_LENGTH);
        assertThatThrownBy(() -> read(failedWith(reason))).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsAFailureReasonOfOnlyEmojiOverTheLimit() {
        final String reason = EMOJI.repeat(TestProperties.MAX_FAILURE_REASON_LENGTH);

        assertThatThrownBy(() -> read(failedWith(reason))).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsAMissingFailureReason() {
        assertInvalid(modified(EventType.ANALYSIS_FAILED,
                e -> ((ObjectNode) e.get("payload")).remove("reason")), "reason is missing");
    }

    @Test
    void rejectsACompletedEventWithAnUnknownRiskCategory() {
        assertInvalid(modified(EventType.ANALYSIS_COMPLETED,
                e -> ((ObjectNode) e.get("payload").get("findings").get(0)).put("category", "WEATHER")), "Invalid");
    }

    private static ObjectNode failedWith(final String reason) {
        final ObjectNode event = Contracts.example(EventType.ANALYSIS_FAILED);
        ((ObjectNode) event.get("payload")).put("reason", reason);
        return event;
    }

    private AnalysisEvent read(final ObjectNode event) {
        return reader.read(event.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] modified(final EventType type, final Consumer<ObjectNode> change) {
        final ObjectNode event = Contracts.example(type);
        change.accept(event);
        return event.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void assertInvalid(final byte[] body, final String message) {
        assertThatThrownBy(() -> reader.read(body)).isInstanceOf(InvalidEventException.class).hasMessageContaining(message);
    }
}
