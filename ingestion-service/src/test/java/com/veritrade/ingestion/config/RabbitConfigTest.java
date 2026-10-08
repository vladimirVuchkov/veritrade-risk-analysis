package com.veritrade.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.veritrade.ingestion.messaging.InvalidEventException;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.retry.RetryPolicySettings;
import tools.jackson.databind.json.JsonMapper;

/** The declarations must match docs/contracts/messaging-topology.md exactly, or RabbitMQ refuses them. */
class RabbitConfigTest {

    private final RabbitConfig config = new RabbitConfig();

    @Test
    void declaresTheEventsExchangeAsDurableTopic() {
        TopicExchange exchange = config.eventsExchange();

        assertThat(exchange.getName()).isEqualTo("veritrade.events");
        assertThat(exchange.getType()).isEqualTo(ExchangeTypes.TOPIC);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
        assertThat(exchange.getArguments()).isEmpty();
    }

    @Test
    void declaresTheDeadLetterExchangeAsDurableDirect() {
        DirectExchange exchange = config.deadLetterExchange();

        assertThat(exchange.getName()).isEqualTo("veritrade.dlx");
        assertThat(exchange.getType()).isEqualTo(ExchangeTypes.DIRECT);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    @Test
    void declaresTheWorkQueueWithExactlyTheDeadLetterArguments() {
        Queue queue = config.analysisEventsQueue();

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
        Queue queue = config.analysisEventsDeadLetterQueue();

        assertThat(queue.getName()).isEqualTo("ingestion.analysis-events.dlq");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.isExclusive()).isFalse();
        assertThat(queue.isAutoDelete()).isFalse();
        assertThat(queue.getArguments()).isEmpty();
    }

    @Test
    void bindsTheWorkQueueToEveryAnalysisEvent() {
        Binding binding = config.analysisEventsBinding(config.analysisEventsQueue(), config.eventsExchange());

        assertThat(binding.getDestination()).isEqualTo("ingestion.analysis-events");
        assertThat(binding.getExchange()).isEqualTo("veritrade.events");
        assertThat(binding.getRoutingKey()).isEqualTo("analysis.*");
    }

    @Test
    void bindsTheDeadLetterQueueWithTheWorkQueueName() {
        Binding binding = config.analysisEventsDeadLetterBinding(
                config.analysisEventsDeadLetterQueue(), config.deadLetterExchange());

        assertThat(binding.getDestination()).isEqualTo("ingestion.analysis-events.dlq");
        assertThat(binding.getExchange()).isEqualTo("veritrade.dlx");
        assertThat(binding.getRoutingKey()).isEqualTo("ingestion.analysis-events");
    }

    @Test
    void templateUsesTheJacksonJsonConverterAndTheRecovererRejects() {
        RabbitTemplate template = new RabbitTemplate();
        config.jsonTemplateConverter(JsonMapper.builder().build()).customize(template);

        assertThat(template.getMessageConverter()).isInstanceOf(JacksonJsonMessageConverter.class);
        assertThat(config.messageRecoverer()).isInstanceOf(RejectAndDontRequeueRecoverer.class);
    }

    @Test
    void retriesTransientFailures() {
        Predicate<Throwable> retry = retryPredicate();

        assertThat(retry.test(new IllegalStateException("database down"))).isTrue();
        assertThat(retry.test(wrapped(new IllegalStateException("database down")))).isTrue();
    }

    @Test
    void doesNotRetryInvalidEventsEvenWhenWrapped() {
        Predicate<Throwable> retry = retryPredicate();

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
        RetryPolicySettings settings = new RetryPolicySettings();
        config.noRetryForInvalidEvents().customize(settings);
        return settings.getExceptionPredicate();
    }

    private static ListenerExecutionFailedException wrapped(Throwable cause) {
        return new ListenerExecutionFailedException("Listener failed", cause, new Message(new byte[0]));
    }
}
