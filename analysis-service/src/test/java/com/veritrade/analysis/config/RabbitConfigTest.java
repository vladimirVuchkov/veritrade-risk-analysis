package com.veritrade.analysis.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.analysis.messaging.InvalidFilingMessageException;
import com.veritrade.contracts.messaging.MessagingTopology;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.boot.retry.RetryPolicySettings;

class RabbitConfigTest {

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void declaresBothExchangesDurableAndNotAutoDeleted() {
        TopicExchange events = config.eventsExchange();
        DirectExchange deadLetters = config.deadLetterExchange();

        assertThat(events.getName()).isEqualTo(MessagingTopology.EVENTS_EXCHANGE);
        assertThat(events.getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(deadLetters.getName()).isEqualTo(MessagingTopology.DEAD_LETTER_EXCHANGE);
        assertThat(deadLetters.getType()).isEqualTo(ExchangeTypes.DIRECT);
        assertThat(events.isDurable() && deadLetters.isDurable()).isTrue();
        assertThat(events.isAutoDelete() || deadLetters.isAutoDelete()).isFalse();
    }

    @Test
    void declaresTheWorkQueueWithExactlyTheDeadLetterArguments() {
        Queue queue = config.filingSubmittedQueue();

        assertThat(queue.getName()).isEqualTo("analysis.filing-submitted");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "x-dead-letter-exchange", "veritrade.dlx",
                "x-dead-letter-routing-key", "analysis.filing-submitted"));
    }

    @Test
    void declaresTheDeadLetterQueueWithoutArguments() {
        Queue dlq = config.filingSubmittedDeadLetterQueue();

        assertThat(dlq.getName()).isEqualTo("analysis.filing-submitted.dlq");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.getArguments()).isEmpty();
    }

    @Test
    void bindsTheQueuesWithTheDocumentedKeys() {
        Binding work = config.filingSubmittedBinding(config.filingSubmittedQueue(), config.eventsExchange());
        Binding deadLetter = config.filingSubmittedDeadLetterBinding(
                config.filingSubmittedDeadLetterQueue(), config.deadLetterExchange());

        assertThat(work.getExchange()).isEqualTo("veritrade.events");
        assertThat(work.getRoutingKey()).isEqualTo("filing.submitted");
        assertThat(work.getDestination()).isEqualTo("analysis.filing-submitted");
        assertThat(deadLetter.getExchange()).isEqualTo("veritrade.dlx");
        assertThat(deadLetter.getRoutingKey()).isEqualTo("analysis.filing-submitted");
        assertThat(deadLetter.getDestination()).isEqualTo("analysis.filing-submitted.dlq");
    }

    @Test
    void retriesProcessingFailuresButNotInvalidMessages() {
        RetryPolicySettings settings = new RetryPolicySettings();
        config.skipRetryForInvalidMessages().customize(settings);
        Predicate<Throwable> retryable = settings.getExceptionPredicate();
        Message message = new Message(new byte[0]);

        assertThat(retryable.test(new ListenerExecutionFailedException("x",
                new InvalidFilingMessageException("bad"), message))).isFalse();
        assertThat(retryable.test(new InvalidFilingMessageException("bad"))).isFalse();
        assertThat(retryable.test(new ListenerExecutionFailedException("x",
                new IllegalStateException("engine"), message))).isTrue();
    }
}
