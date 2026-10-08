package com.veritrade.reporting.config;

import com.veritrade.contracts.messaging.MessagingTopology;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares exactly the topology Reporting owns or uses, as specified in
 * docs/contracts/messaging-topology.md. Retry settings come from application.yml.
 */
@Configuration(proxyBeanMethods = false)
public class RabbitConfig {

    private static final String WORK_QUEUE = MessagingTopology.Q_REPORTING_ANALYSIS_RESULTS;
    private static final String DEAD_LETTER_QUEUE = MessagingTopology.deadLetterQueue(WORK_QUEUE);

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(MessagingTopology.EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(MessagingTopology.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue analysisResultsQueue() {
        return QueueBuilder.durable(WORK_QUEUE)
                .withArgument(MessagingTopology.ARG_DEAD_LETTER_EXCHANGE, MessagingTopology.DEAD_LETTER_EXCHANGE)
                .withArgument(MessagingTopology.ARG_DEAD_LETTER_ROUTING_KEY, WORK_QUEUE)
                .build();
    }

    @Bean
    Queue analysisResultsDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding analysisCompletedBinding(Queue analysisResultsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(analysisResultsQueue).to(eventsExchange)
                .with(MessagingTopology.RK_ANALYSIS_COMPLETED);
    }

    @Bean
    Binding analysisFailedBinding(Queue analysisResultsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(analysisResultsQueue).to(eventsExchange)
                .with(MessagingTopology.RK_ANALYSIS_FAILED);
    }

    @Bean
    Binding analysisResultsDeadLetterBinding(Queue analysisResultsDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(analysisResultsDeadLetterQueue).to(deadLetterExchange).with(WORK_QUEUE);
    }

    /** Unreadable or invalid messages are not retried: the recoverer sends them to the dead-letter queue at once. */
    @Bean
    RabbitListenerRetrySettingsCustomizer noRetryForUnreadableMessages() {
        return settings -> settings.setExceptionPredicate(RabbitConfig::isRetryable);
    }

    static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AmqpRejectAndDontRequeueException || cause instanceof MessageConversionException) {
                return false;
            }
        }
        return true;
    }
}
