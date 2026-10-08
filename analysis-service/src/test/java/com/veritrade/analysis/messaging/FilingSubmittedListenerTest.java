package com.veritrade.analysis.messaging;

import static com.veritrade.analysis.support.TestMessages.filingSubmitted;
import static com.veritrade.analysis.support.TestMessages.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.veritrade.analysis.engine.RiskAnalyzer;
import com.veritrade.analysis.support.TestMessages;
import com.veritrade.contracts.event.AnalysisCompletedPayload;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.logging.CorrelationIds;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.contracts.model.RiskLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class FilingSubmittedListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:01Z");

    private final AnalysisEventPublisher publisher = mock(AnalysisEventPublisher.class);
    private final FilingSubmittedReader reader = new FilingSubmittedReader(JsonMapper.builder().build());
    private final AnalysisEventFactory events = new AnalysisEventFactory(Clock.fixed(NOW, ZoneOffset.UTC));
    private final List<EventEnvelope<?>> published = new ArrayList<>();
    private final List<String> correlationIdsInLog = new ArrayList<>();

    private RiskAnalyzer analyzer = TestMessages.bundledAnalyzer();

    @BeforeEach
    void recordPublishedEvents() {
        doAnswer(invocation -> {
            published.add(invocation.getArgument(0));
            correlationIdsInLog.add(MDC.get(CorrelationIds.MDC_KEY));
            return null;
        }).when(publisher).publish(any());
    }

    @Test
    void publishesStartedThenCompletedForAValidEvent() {
        UUID filingId = UUID.randomUUID();

        listener().onFilingSubmitted(message(filingSubmitted(filingId)));

        assertThat(published).extracting(EventEnvelope::eventType)
                .containsExactly(EventType.ANALYSIS_STARTED, EventType.ANALYSIS_COMPLETED);
        assertThat(published).extracting(EventEnvelope::eventId).containsExactly(
                EventIds.forFiling(filingId, EventType.ANALYSIS_STARTED),
                EventIds.forFiling(filingId, EventType.ANALYSIS_COMPLETED));
        assertThat(published).extracting(EventEnvelope::correlationId)
                .containsOnly("c0a8012e-5b1f-4d3c-8e2a-7f6b9d4c1a20");
        AnalysisCompletedPayload completed = (AnalysisCompletedPayload) published.getLast().payload();
        assertThat(completed.filingId()).isEqualTo(filingId);
        assertThat(completed.summary().totalFindings()).isEqualTo(3);
        assertThat(completed.summary().overallRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void putsTheCorrelationIdInTheLoggingContextOnlyWhileProcessing() {
        listener().onFilingSubmitted(message(filingSubmitted(UUID.randomUUID())));

        assertThat(correlationIdsInLog).containsOnly("c0a8012e-5b1f-4d3c-8e2a-7f6b9d4c1a20");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @Test
    void ignoresUnknownFields() {
        ObjectNode event = filingSubmitted(UUID.randomUUID());
        event.put("addedInVersion2", "ignored");
        event.putObject("metadata").put("source", "batch");
        ((ObjectNode) event.get("payload")).put("industry", "Manufacturing");

        listener().onFilingSubmitted(message(event));

        assertThat(published).hasSize(2);
    }

    @Test
    void acceptsANewerEventVersion() {
        ObjectNode event = filingSubmitted(UUID.randomUUID());
        event.put("eventVersion", 2);

        listener().onFilingSubmitted(message(event));

        assertThat(published).hasSize(2);
    }

    @Test
    void acceptsWhitespaceOnlyContentAndReportsNoRisk() {
        ObjectNode event = filingSubmitted(UUID.randomUUID());
        ((ObjectNode) event.get("payload")).put("content", "   ");

        listener().onFilingSubmitted(message(event));

        AnalysisCompletedPayload completed = (AnalysisCompletedPayload) published.getLast().payload();
        assertThat(completed.findings()).isEmpty();
        assertThat(completed.summary().overallRiskLevel()).isEqualTo(RiskLevel.NONE);
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"eventId", "eventType", "eventVersion", "occurredAt", "correlationId", "payload"})
    void rejectsAnEnvelopeWithAMissingField(String field) {
        ObjectNode event = filingSubmitted(UUID.randomUUID());
        event.remove(field);

        assertInvalid(event.toString());
    }

    @ParameterizedTest(name = "missing payload.{0}")
    @ValueSource(strings = {"filingId", "companyName", "title", "content", "submittedAt"})
    void rejectsAPayloadWithAMissingField(String field) {
        ObjectNode event = filingSubmitted(UUID.randomUUID());
        ((ObjectNode) event.get("payload")).remove(field);

        assertThatThrownBy(() -> listener().onFilingSubmitted(message(event)))
                .isInstanceOf(InvalidFilingMessageException.class)
                .hasMessageContaining("payload." + field);
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidValues")
    void rejectsEmptyOrMalformedValues(String problem, String path, String value) {
        ObjectNode event = filingSubmitted(UUID.randomUUID());
        ObjectNode target = path.startsWith("payload.") ? (ObjectNode) event.get("payload") : event;
        target.put(path.replace("payload.", ""), value);

        assertInvalid(event.toString());
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("empty content", "payload.content", ""),
                Arguments.of("blank company name", "payload.companyName", " "),
                Arguments.of("blank title", "payload.title", ""),
                Arguments.of("blank correlation id", "correlationId", " "),
                Arguments.of("filing id is not a UUID", "payload.filingId", "not-a-uuid"),
                Arguments.of("event id is not a UUID", "eventId", "123"),
                Arguments.of("submittedAt is not a timestamp", "payload.submittedAt", "yesterday"),
                Arguments.of("unknown event type", "eventType", "FILING_DELETED"),
                Arguments.of("event version zero", "eventVersion", "0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANALYSIS_STARTED", "ANALYSIS_COMPLETED", "ANALYSIS_FAILED"})
    void rejectsAnotherEventTypeOnTheQueue(String eventType) {
        ObjectNode event = filingSubmitted(UUID.randomUUID());
        event.put("eventType", eventType);

        assertThatThrownBy(() -> listener().onFilingSubmitted(message(event)))
                .isInstanceOf(InvalidFilingMessageException.class)
                .hasMessageContaining("Unexpected eventType " + eventType);
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "", "null", "[]", "42", "\"text\"", "{\"eventType\":"})
    void rejectsMalformedJson(String body) {
        assertInvalid(body);
    }

    @Test
    void letsAProcessingFailurePropagateSoTheRetryCanHandleIt() {
        analyzer = mock(RiskAnalyzer.class);
        when(analyzer.analyze(anyString())).thenThrow(new IllegalStateException("rule engine error"));

        assertThatThrownBy(() -> listener().onFilingSubmitted(message(filingSubmitted(UUID.randomUUID()))))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(InvalidFilingMessageException.class);
        assertThat(published).extracting(EventEnvelope::eventType).containsExactly(EventType.ANALYSIS_STARTED);
    }

    @Test
    void aRedeliveredFilingProducesEventsWithTheSameIds() {
        ObjectNode event = filingSubmitted(UUID.randomUUID());

        listener().onFilingSubmitted(message(event.toString(), false));
        listener().onFilingSubmitted(message(event.toString(), true));

        assertThat(published).hasSize(4);
        assertThat(published.get(2).eventId()).isEqualTo(published.get(0).eventId());
        assertThat(published.get(3).eventId()).isEqualTo(published.get(1).eventId());
        assertThat(published.get(3).payload()).isEqualTo(published.get(1).payload());
        verify(publisher, times(4)).publish(any());
    }

    private void assertInvalid(String body) {
        assertThatThrownBy(() -> listener().onFilingSubmitted(message(body, false)))
                .isInstanceOf(InvalidFilingMessageException.class);
        verifyNoInteractions(publisher);
    }

    private FilingSubmittedListener listener() {
        return new FilingSubmittedListener(reader, analyzer, events, publisher);
    }
}
