package com.veritrade.analysis.messaging;

import static com.veritrade.analysis.support.TestMessages.filingSubmitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.veritrade.analysis.engine.RiskAnalyzer;
import com.veritrade.analysis.support.RabbitIntegrationTest;
import com.veritrade.analysis.support.TestMessages;
import com.veritrade.contracts.event.EventType;
import com.veritrade.contracts.messaging.EventIds;
import com.veritrade.contracts.messaging.MessagingTopology;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.node.ObjectNode;

/**
 * The third failure outcome: processing fails after the last attempt and the broker refuses
 * {@code analysis.failed} as well, so the original {@code filing.submitted} goes to the dead-letter queue.
 * The refusal is a real negative publisher confirm: the only queue bound to {@code analysis.failed} is full
 * ({@code x-max-length: 0}) and rejects new messages ({@code x-overflow: reject-publish}). The capture queue
 * sees only {@code analysis.started} and {@code analysis.completed} in these tests.
 */
class AnalysisFailedUnpublishableIT extends RabbitIntegrationTest {

    private static final String FULL_QUEUE = "it.analysis-failed-full";
    private static final TopicExchange EVENTS = new TopicExchange(MessagingTopology.EVENTS_EXCHANGE);
    private static final Binding CAPTURE_ALL = captureBinding(MessagingTopology.RK_ANALYSIS_ALL);
    private static final List<Binding> CAPTURE_STARTED_AND_COMPLETED = List.of(
            captureBinding(EventType.ANALYSIS_STARTED.routingKey()),
            captureBinding(EventType.ANALYSIS_COMPLETED.routingKey()));

    @MockitoBean
    private RiskAnalyzer analyzer;

    @MockitoSpyBean
    private AnalysisEventPublisher publisher;

    @Value("${spring.rabbitmq.listener.simple.retry.max-retries}")
    private int maxRetries;

    @Value("${spring.rabbitmq.listener.simple.retry.initial-interval}")
    private Duration initialInterval;

    @Value("${spring.rabbitmq.listener.simple.retry.multiplier}")
    private double multiplier;

    @Value("${spring.rabbitmq.listener.simple.retry.max-interval}")
    private Duration maxInterval;

    @BeforeEach
    void refuseAnalysisFailed() {
        admin.removeBinding(CAPTURE_ALL);
        CAPTURE_STARTED_AND_COMPLETED.forEach(admin::declareBinding);
        Queue full = QueueBuilder.durable(FULL_QUEUE).maxLength(0).overflow(QueueBuilder.Overflow.rejectPublish).build();
        admin.declareQueue(full);
        admin.declareBinding(BindingBuilder.bind(full).to(EVENTS).with(EventType.ANALYSIS_FAILED.routingKey()));
    }

    @AfterEach
    void restoreBindings() {
        admin.deleteQueue(FULL_QUEUE);
        CAPTURE_STARTED_AND_COMPLETED.forEach(admin::removeBinding);
        admin.declareBinding(CAPTURE_ALL);
    }

    @Test
    void deadLettersTheFilingWhenAnalysisFailedIsNackedAndKeepsConsuming() {
        when(analyzer.analyze(anyString())).thenThrow(new IllegalStateException("rule engine error"));
        UUID filingId = UUID.randomUUID();
        ObjectNode filing = filingSubmitted(filingId);

        sendFilingSubmitted(filing.toString());

        Message dead = receive(DEAD_LETTER_QUEUE);
        assertThat(json(dead)).isEqualTo(filing);
        assertRejectedOnce(dead);
        verify(analyzer, times(attempts())).analyze(anyString());
        verify(publisher, times(1)).publish(argThat(event -> event.eventType() == EventType.ANALYSIS_FAILED));
        for (int attempt = 0; attempt < attempts(); attempt++) {
            assertThat(receive(CAPTURE_QUEUE).getMessageProperties().getMessageId())
                    .isEqualTo(EventIds.forFiling(filingId, EventType.ANALYSIS_STARTED).toString());
        }
        assertNoMoreMessages(CAPTURE_QUEUE);
        assertNoMoreMessages(FULL_QUEUE);

        assertTheNextValidFilingIsAnalysed();
    }

    @Test
    void waitsTheConfiguredExponentialBackoffBetweenAttempts() {
        List<Long> attemptTimes = new CopyOnWriteArrayList<>();
        when(analyzer.analyze(anyString())).thenAnswer(invocation -> {
            attemptTimes.add(System.nanoTime());
            throw new IllegalStateException("rule engine error");
        });

        sendFilingSubmitted(filingSubmitted(UUID.randomUUID()).toString());

        receive(DEAD_LETTER_QUEUE);
        assertThat(attemptTimes).hasSize(attempts());
        for (int retry = 1; retry < attempts(); retry++) {
            Duration gap = Duration.ofNanos(attemptTimes.get(retry) - attemptTimes.get(retry - 1));
            assertThat(gap).as("pause before retry %d", retry).isGreaterThanOrEqualTo(expectedDelay(retry));
        }
    }

    private void assertTheNextValidFilingIsAnalysed() {
        RiskAnalyzer real = TestMessages.bundledAnalyzer();
        doAnswer(invocation -> real.analyze(invocation.getArgument(0))).when(analyzer).analyze(anyString());
        UUID next = UUID.randomUUID();

        sendFilingSubmitted(filingSubmitted(next).toString());

        assertThat(receive(CAPTURE_QUEUE).getMessageProperties().getMessageId())
                .isEqualTo(EventIds.forFiling(next, EventType.ANALYSIS_STARTED).toString());
        assertThat(receive(CAPTURE_QUEUE).getMessageProperties().getMessageId())
                .isEqualTo(EventIds.forFiling(next, EventType.ANALYSIS_COMPLETED).toString());
        assertNoMoreMessages(DEAD_LETTER_QUEUE);
    }

    private int attempts() {
        return maxRetries + 1;
    }

    /** The pause before the given retry (1-based): initial interval times multiplier^(retry - 1), capped. */
    private Duration expectedDelay(int retry) {
        long millis = (long) (initialInterval.toMillis() * Math.pow(multiplier, retry - 1));
        return Duration.ofMillis(Math.min(millis, maxInterval.toMillis()));
    }

    private static Binding captureBinding(String routingKey) {
        return BindingBuilder.bind(new Queue(CAPTURE_QUEUE)).to(EVENTS).with(routingKey);
    }

    @SuppressWarnings("unchecked")
    private static void assertRejectedOnce(Message dead) {
        List<Map<String, Object>> deaths =
                (List<Map<String, Object>>) dead.getMessageProperties().getHeaders().get("x-death");
        assertThat(deaths).singleElement().satisfies(death -> {
            assertThat(death.get("queue")).isEqualTo(MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED);
            assertThat(death.get("reason")).isEqualTo("rejected");
            assertThat(((Number) death.get("count")).intValue()).isEqualTo(1);
        });
    }
}
