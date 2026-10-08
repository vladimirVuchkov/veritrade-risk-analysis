package com.veritrade.analysis.messaging;

import static com.veritrade.analysis.support.TestMessages.filingSubmitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.veritrade.analysis.engine.RiskAnalyzer;
import com.veritrade.analysis.support.ContractFixtures;
import com.veritrade.analysis.support.RabbitIntegrationTest;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/** A processing failure that survives all retries publishes analysis.failed and acknowledges the message. */
class AnalysisFailureIT extends RabbitIntegrationTest {

    private static final int ATTEMPTS = 3;
    private static final String REASON_PREFIX = "Analysis failed after all retries: ";
    /** One character outside the Basic Multilingual Plane: two UTF-16 code units. */
    private static final String EMOJI = "\uD83D\uDE00";

    @MockitoBean
    private RiskAnalyzer analyzer;

    @Test
    void publishesAnalysisFailedAfterTheLastAttemptAndAcknowledges() {
        when(analyzer.analyze(anyString())).thenThrow(new IllegalStateException("rule engine error"));
        UUID filingId = UUID.randomUUID();

        sendFilingSubmitted(filingSubmitted(filingId).toString());

        List<Message> events = receiveEventsUntil(EventType.ANALYSIS_FAILED);
        Message failed = events.getLast();
        JsonNode failedEvent = json(failed);
        assertThat(ContractFixtures.validate(EventType.ANALYSIS_FAILED, failedEvent)).isEmpty();
        assertThat(failed.getMessageProperties().getMessageId())
                .isEqualTo(EventIds.forFiling(filingId, EventType.ANALYSIS_FAILED).toString());
        assertThat(failedEvent.get("payload").get("reason").asString())
                .isEqualTo(REASON_PREFIX + "IllegalStateException: rule engine error");
        assertThat(events.subList(0, events.size() - 1))
                .extracting(m -> m.getMessageProperties().getMessageId())
                .containsOnly(EventIds.forFiling(filingId, EventType.ANALYSIS_STARTED).toString());
        verify(analyzer, times(ATTEMPTS)).analyze(anyString());
        await().atMost(RECEIVE_TIMEOUT).untilAsserted(() ->
                assertThat(messageCount(MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED)).isZero());
        assertNoMoreMessages(DEAD_LETTER_QUEUE);
        assertNoMoreMessages(CAPTURE_QUEUE);
    }

    @Test
    void cutsALongReasonToTheSchemaLimitWithoutSplittingASurrogatePair() {
        String fixedPart = REASON_PREFIX + "IllegalStateException: ";
        String detail = "x".repeat(MessagingProperties.SCHEMA_MAX_REASON_LENGTH - fixedPart.length() - 1)
                + EMOJI.repeat(MessagingProperties.SCHEMA_MAX_REASON_LENGTH);
        when(analyzer.analyze(anyString())).thenThrow(new IllegalStateException(detail));

        sendFilingSubmitted(filingSubmitted(UUID.randomUUID()).toString());

        JsonNode failedEvent = json(receiveEventsUntil(EventType.ANALYSIS_FAILED).getLast());
        String reason = failedEvent.get("payload").get("reason").asString();
        assertThat(ContractFixtures.validate(EventType.ANALYSIS_FAILED, failedEvent)).isEmpty();
        assertThat(reason).hasSize(MessagingProperties.SCHEMA_MAX_REASON_LENGTH - 1)
                .isEqualTo((fixedPart + detail).substring(0, MessagingProperties.SCHEMA_MAX_REASON_LENGTH - 1));
        assertNoMoreMessages(DEAD_LETTER_QUEUE);
    }

    private long messageCount(String queue) {
        QueueInformation info = admin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }
}
