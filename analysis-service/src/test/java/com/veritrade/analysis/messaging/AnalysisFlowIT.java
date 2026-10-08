package com.veritrade.analysis.messaging;

import static com.veritrade.analysis.support.TestMessages.filingSubmitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.veritrade.analysis.support.ContractFixtures;
import com.veritrade.analysis.support.RabbitIntegrationTest;
import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class AnalysisFlowIT extends RabbitIntegrationTest {

    private static final String CORRELATION_ID = "c0a8012e-5b1f-4d3c-8e2a-7f6b9d4c1a20";

    private static final String TYPE_ID_HEADER = "__TypeId__";

    @MockitoSpyBean
    private FilingSubmittedReader reader;

    @MockitoSpyBean
    private FilingSubmittedListener listener;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void resetSpy() {
        clearInvocations(reader, listener);
    }

    @Test
    void publishesStartedAndCompletedThatMatchTheSchemas() {
        final UUID filingId = UUID.randomUUID();

        sendFilingSubmitted(filingSubmitted(filingId).toString());

        final Message started = receive(CAPTURE_QUEUE);
        final Message completed = receive(CAPTURE_QUEUE);
        assertEvent(started, EventType.ANALYSIS_STARTED, filingId);
        assertEvent(completed, EventType.ANALYSIS_COMPLETED, filingId);
        final JsonNode summary = json(completed).get("payload").get("summary");
        assertThat(summary.get("totalFindings").asInt()).isEqualTo(3);
        assertThat(summary.get("overallRiskLevel").asString()).isEqualTo("HIGH");
        assertNoMoreMessages(CAPTURE_QUEUE);
    }

    @Test
    void processesTheContractExampleIntoTheContractResult() {
        sendFilingSubmitted(ContractFixtures.example(EventType.FILING_SUBMITTED).toString());

        receive(CAPTURE_QUEUE);
        final JsonNode completed = json(receive(CAPTURE_QUEUE));

        final JsonNode expected = ContractFixtures.example(EventType.ANALYSIS_COMPLETED);
        assertThat(completed.get("eventId")).isEqualTo(expected.get("eventId"));
        assertThat(completed.get("payload").get("findings")).isEqualTo(expected.get("payload").get("findings"));
        assertThat(completed.get("payload").get("summary")).isEqualTo(expected.get("payload").get("summary"));
        assertThat(completed.get("payload").get("rulesVersion")).isEqualTo(expected.get("payload").get("rulesVersion"));
    }

    @Test
    void sendsAnUnreadableMessageToTheDeadLetterQueueWithoutRetries() {
        sendFilingSubmitted("{\"eventType\": \"FILING_SUBMITTED\", \"payload\": {broken");

        final Message dead = receive(DEAD_LETTER_QUEUE);

        assertThat(new String(dead.getBody())).contains("broken");
        assertRejectedOnce(dead);
        verify(listener, times(1)).onFilingSubmitted(any());
        verify(reader, times(1)).read(any());
        assertNoMoreMessages(CAPTURE_QUEUE);
    }

    @Test
    void sendsAnEventWithMissingFieldsToTheDeadLetterQueueWithoutRetries() {
        final ObjectNode event = filingSubmitted(UUID.randomUUID());
        ((ObjectNode) event.get("payload")).remove("content");

        sendFilingSubmitted(event.toString());

        assertRejectedOnce(receive(DEAD_LETTER_QUEUE));
        verify(listener, times(1)).onFilingSubmitted(any());
        verify(reader, times(1)).read(any());
        assertNoMoreMessages(CAPTURE_QUEUE);
    }

    @Test
    void sendsANewerEventVersionToTheDeadLetterQueueWithoutRetriesOrEvents() {
        final ObjectNode event = filingSubmitted(UUID.randomUUID());
        event.put("eventVersion", EventEnvelope.CURRENT_VERSION + 1);

        sendFilingSubmitted(event.toString());

        final Message dead = receive(DEAD_LETTER_QUEUE);
        assertThat(json(dead)).isEqualTo(event);
        assertRejectedOnce(dead);
        verify(listener, times(1)).onFilingSubmitted(any());
        verify(reader, times(1)).read(any());
        assertNoMoreMessages(CAPTURE_QUEUE);
    }

    @Test
    void sendsAWrongEventTypeToTheDeadLetterQueue() {
        final ObjectNode event = filingSubmitted(UUID.randomUUID());
        event.put("eventType", "ANALYSIS_COMPLETED");

        sendFilingSubmitted(event.toString());

        assertRejectedOnce(receive(DEAD_LETTER_QUEUE));
        assertNoMoreMessages(CAPTURE_QUEUE);
    }

    @Test
    void aDuplicateDeliveryRepublishesEventsWithTheSameIds() {
        final String body = filingSubmitted(UUID.randomUUID()).toString();

        sendFilingSubmitted(body);
        final List<Message> first = List.of(receive(CAPTURE_QUEUE), receive(CAPTURE_QUEUE));
        sendFilingSubmitted(body);
        final List<Message> second = List.of(receive(CAPTURE_QUEUE), receive(CAPTURE_QUEUE));

        assertThat(second).extracting(m -> m.getMessageProperties().getMessageId())
                .isEqualTo(first.stream().map(m -> m.getMessageProperties().getMessageId()).toList());
        assertThat(json(second.getLast()).get("payload").get("findings"))
                .isEqualTo(json(first.getLast()).get("payload").get("findings"));
    }

    @Test
    void ignoresUnknownFieldsInTheIncomingEvent() {
        final ObjectNode event = filingSubmitted(UUID.randomUUID());
        event.put("addedLater", true);
        ((ObjectNode) event.get("payload")).put("industry", "Retail");

        sendFilingSubmitted(event.toString());

        assertThat(eventType(receive(CAPTURE_QUEUE))).isEqualTo(EventType.ANALYSIS_STARTED);
        assertThat(eventType(receive(CAPTURE_QUEUE))).isEqualTo(EventType.ANALYSIS_COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"com.veritrade.contracts.event.EventEnvelope", "java.lang.Runtime", "com.example.Missing"})
    void ignoresAJavaTypeHeaderOnAValidFiling(final String typeId) {
        final UUID filingId = UUID.randomUUID();

        sendFilingSubmitted(filingSubmitted(filingId).toString(), Map.of(TYPE_ID_HEADER, typeId));

        assertEvent(receive(CAPTURE_QUEUE), EventType.ANALYSIS_STARTED, filingId);
        assertEvent(receive(CAPTURE_QUEUE), EventType.ANALYSIS_COMPLETED, filingId);
        verify(listener, times(1)).onFilingSubmitted(any());
        assertNoMoreMessages(CAPTURE_QUEUE);
    }

    @Test
    void theListenerContainerGetsNoJsonConverter() {
        assertThat(context.getBeansOfType(MessageConverter.class)).isEmpty();
        assertThat(rabbitTemplate.getMessageConverter()).isInstanceOf(JacksonJsonMessageConverter.class);
    }

    private static void assertEvent(final Message message, final EventType type, final UUID filingId) {
        final JsonNode event = json(message);
        final MessageProperties properties = message.getMessageProperties();
        final String eventId = EventIds.forFiling(filingId, type).toString();
        assertThat(ContractFixtures.validate(type, event)).isEmpty();
        assertThat(event.get("eventType").asString()).isEqualTo(type.name());
        assertThat(event.get("eventId").asString()).isEqualTo(eventId);
        assertThat(event.get("payload").get("filingId").asString()).isEqualTo(filingId.toString());
        assertThat(properties.getReceivedRoutingKey()).isEqualTo(type.routingKey());
        assertThat(properties.getMessageId()).isEqualTo(eventId);
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @SuppressWarnings("unchecked")
    private static void assertRejectedOnce(final Message dead) {
        final List<Map<String, Object>> deaths = (List<Map<String, Object>>) dead.getMessageProperties().getHeaders().get("x-death");
        assertThat(deaths).singleElement().satisfies(death -> {
            assertThat(death.get("queue")).isEqualTo(MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED);
            assertThat(death.get("reason")).isEqualTo("rejected");
            assertThat(((Number) death.get("count")).intValue()).isEqualTo(1);
        });
    }
}
