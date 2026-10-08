package com.veritrade.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.ingestion.messaging.InvalidEventException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.retry.RetryPolicySettings;

/** The declarations must match docs/contracts/messaging-topology.md exactly, or RabbitMQ refuses them. */
class RabbitConfigTest {

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void declaresTheEventsExchangeAsDurableTopic() {
        final TopicExchange exchange = config.eventsExchange();

        assertThat(exchange.getName()).isEqualTo("veritrade.events");
        assertThat(exchange.getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
        assertThat(exchange.getArguments()).isEmpty();
    }

    @Test
    void declaresTheDeadLetterExchangeAsDurableDirect() {
        final DirectExchange exchange = config.deadLetterExchange();

        assertThat(exchange.getName()).isEqualTo("veritrade.dlx");
        assertThat(exchange.getType()).isEqualTo(ExchangeTypes.DIRECT);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    void declaresTheWorkQueueWithExactlyTheDeadLetterArguments() {
        final Queue queue = config.analysisEventsQueue();

        assertThat(queue.getName()).isEqualTo("ingestion.analysis-events");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "x-dead-letter-exchange", "veritrade.dlx",
                "x-dead-letter-routing-key", "ingestion.analysis-events"));
    }

    @Test
    void declaresTheDeadLetterQueueWithoutArguments() {
        final Queue queue = config.analysisEventsDeadLetterQueue();

        assertThat(queue.getName()).isEqualTo("ingestion.analysis-events.dlq");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments()).isEmpty();
    }

    @Test
    void bindsTheWorkQueueToEveryAnalysisEvent() {
        final Binding binding = config.analysisEventsBinding(config.analysisEventsQueue(), config.eventsExchange());

        assertThat(binding.getDestination()).isEqualTo("ingestion.analysis-events");
        assertThat(binding.getExchange()).isEqualTo("veritrade.events");
        assertThat(binding.getRoutingKey()).isEqualTo("analysis.*");
    }

    @Test
    void bindsTheDeadLetterQueueWithTheWorkQueueName() {
        final Binding binding = config.analysisEventsDeadLetterBinding(
                config.analysisEventsDeadLetterQueue(), config.deadLetterExchange());

        assertThat(binding.getDestination()).isEqualTo("ingestion.analysis-events.dlq");
        assertThat(binding.getExchange()).isEqualTo("veritrade.dlx");
        assertThat(binding.getRoutingKey()).isEqualTo("ingestion.analysis-events");
    }

    @Test
    void recovererRejectsSoTheMessageGoesToTheDeadLetterQueue() {
        assertThat(config.messageRecoverer()).isInstanceOf(RejectAndDontRequeueRecoverer.class);
    }

    /** A converter here would either be dead code or reach the listener container (see the class Javadoc). */
    @Test
    void declaresNoMessageConverterAndNoTemplateCustomizer() {
        assertThat(Arrays.stream(RabbitConfig.class.getDeclaredMethods()).map(Method::getReturnType))
                .noneMatch(MessageConverter.class::isAssignableFrom)
                .noneMatch(RabbitTemplateCustomizer.class::isAssignableFrom);
    }

    @Test
    void retriesTransientFailures() {
        final Predicate<Throwable> retry = retryPredicate();

        assertThat(retry.test(new IllegalStateException("database down"))).isTrue();
        assertThat(retry.test(wrapped(new IllegalStateException("database down")))).isTrue();
    }

    @Test
    void doesNotRetryInvalidEventsEvenWhenWrapped() {
        final Predicate<Throwable> retry = retryPredicate();

        assertThat(retry.test(new InvalidEventException("bad"))).isFalse();
        assertThat(retry.test(wrapped(new InvalidEventException("bad")))).isFalse();
        assertThat(retry.test(wrapped(new RuntimeException(new InvalidEventException("deep"))))).isFalse();
    }

    @Test
    void doesNotRetryABodyThatCannotBeConverted() {
        assertThat(retryPredicate().test(wrapped(new MessageConversionException("Failed to convert Message content"))))
                .isFalse();
    }

    private Predicate<Throwable> retryPredicate() {
        final RetryPolicySettings settings = new RetryPolicySettings();
        config.noRetryForInvalidEvents().customize(settings);
        return settings.getExceptionPredicate();
    }

    private static ListenerExecutionFailedException wrapped(final Throwable cause) {
        return new ListenerExecutionFailedException("Listener failed", cause, new Message(new byte[0]));
    }
}
