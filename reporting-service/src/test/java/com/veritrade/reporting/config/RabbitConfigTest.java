package com.veritrade.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.contracts.messaging.MessagingTopology;
import com.veritrade.reporting.messaging.InvalidEventException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.retry.RetryPolicySettings;

class RabbitConfigTest {

    private static final String QUEUE = MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS;
    private static final String DLQ = "reporting.analysis-results.dlq";

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void workQueueIsDurableWithExactlyTheDeadLetterArguments() {
        final Queue queue = config.analysisResultsQueue();

        assertThat(queue.getName()).isEqualTo("reporting.analysis-results");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments()).isEqualTo(Map.of(
                "x-dead-letter-exchange", "veritrade.dlx",
                "x-dead-letter-routing-key", QUEUE));
    }

    @Test
    void deadLetterQueueIsDurableWithoutArguments() {
        final Queue dlq = config.analysisResultsDeadLetterQueue();

        assertThat(dlq.getName()).isEqualTo(DLQ);
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.isExclusive()).isFalse();
        assertThat(dlq.isAutoDelete()).isFalse();
        assertThat(dlq.getArguments()).isEmpty();
    }

    @Test
    void exchangesAreDurableAndNotAutoDeleted() {
        final TopicExchange events = config.eventsExchange();
        final DirectExchange dlx = config.deadLetterExchange();

        assertThat(events.getName()).isEqualTo("veritrade.events");
        assertThat(events.isDurable()).isTrue();
        assertThat(events.isAutoDelete()).isFalse();
        assertThat(dlx.getName()).isEqualTo("veritrade.dlx");
        assertThat(dlx.isDurable()).isTrue();
        assertThat(dlx.isAutoDelete()).isFalse();
    }

    @Test
    void bindsCompletedAndFailedOnlyAndTheDeadLetterQueueByQueueName() {
        final Queue queue = config.analysisResultsQueue();
        final TopicExchange events = config.eventsExchange();

        final Binding completed = config.analysisCompletedBinding(queue, events);
        final Binding failed = config.analysisFailedBinding(queue, events);
        final Binding deadLetter = config.analysisResultsDeadLetterBinding(
                config.analysisResultsDeadLetterQueue(), config.deadLetterExchange());

        assertThat(completed.getRoutingKey()).isEqualTo("analysis.completed");
        assertThat(failed.getRoutingKey()).isEqualTo("analysis.failed");
        assertThat(completed.getExchange()).isEqualTo("veritrade.events");
        assertThat(deadLetter.getExchange()).isEqualTo("veritrade.dlx");
        assertThat(deadLetter.getDestination()).isEqualTo(DLQ);
        assertThat(deadLetter.getRoutingKey()).isEqualTo(QUEUE);
    }

    @Test
    void processingFailuresAreRetried() {
        assertThat(RabbitConfig.isRetryable(new IllegalStateException("database down"))).isTrue();
        assertThat(RabbitConfig.isRetryable(
                new ListenerExecutionFailedException("listener failed", new IllegalStateException("db")))).isTrue();
    }

    @Test
    void unreadableMessagesAreNotRetriedEvenWhenWrapped() {
        assertThat(RabbitConfig.isRetryable(new InvalidEventException("bad"))).isFalse();
        assertThat(RabbitConfig.isRetryable(new AmqpRejectAndDontRequeueException("bad"))).isFalse();
        assertThat(RabbitConfig.isRetryable(new MessageConversionException("bad json"))).isFalse();
        assertThat(RabbitConfig.isRetryable(
                new ListenerExecutionFailedException("failed", new MessageConversionException("bad json")))).isFalse();
        assertThat(RabbitConfig.isRetryable(
                new ListenerExecutionFailedException("failed", new InvalidEventException("bad")))).isFalse();
    }

    @Test
    void retryCustomizerInstallsThePredicate() {
        final RetryPolicySettings settings = new RetryPolicySettings();

        config.noRetryForUnreadableMessages().customize(settings);

        assertThat(settings.getExceptionPredicate().test(new InvalidEventException("bad"))).isFalse();
        assertThat(settings.getExceptionPredicate().test(new IllegalStateException("db"))).isTrue();
    }
}
