package com.veritrade.analysis.config;

import static com.veritrade.contracts.messaging.MessagingTopology.DEAD_LETTER_EXCHANGE;
import static com.veritrade.contracts.messaging.MessagingTopology.EVENTS_EXCHANGE;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_ANALYSIS_FILING_SUBMITTED;
import static com.veritrade.contracts.messaging.MessagingTopology.RK_FILING_SUBMITTED;
import static com.veritrade.contracts.messaging.MessagingTopology.deadLetterQueue;

import com.veritrade.analysis.messaging.InvalidFilingMessageException;
import com.veritrade.analysis.messaging.MessagingProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The topology Analysis owns, exactly as in docs/contracts/messaging-topology.md: both exchanges,
 * the work queue {@code analysis.filing-submitted} with its dead-letter arguments, and its
 * dead-letter queue. The listener retry itself is configured in application.yml.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitConfig {

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue filingSubmittedQueue() {
        return QueueBuilder.durable(Q_ANALYSIS_FILING_SUBMITTED)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(Q_ANALYSIS_FILING_SUBMITTED)
                .build();
    }

    @Bean
    Queue filingSubmittedDeadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueue(Q_ANALYSIS_FILING_SUBMITTED)).build();
    }

    @Bean
    Binding filingSubmittedBinding(final Queue filingSubmittedQueue, final TopicExchange eventsExchange) {
        return BindingBuilder.bind(filingSubmittedQueue).to(eventsExchange).with(RK_FILING_SUBMITTED);
    }

    @Bean
    Binding filingSubmittedDeadLetterBinding(final Queue filingSubmittedDeadLetterQueue, final DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(filingSubmittedDeadLetterQueue).to(deadLetterExchange)
                .with(Q_ANALYSIS_FILING_SUBMITTED);
    }

    /**
     * The JSON converter goes on the template only. It is deliberately not a {@code MessageConverter}
     * bean: Spring Boot would also give it to the listener container, which would then convert every
     * body (by the producer's {@code __TypeId__} header) before the listener runs. An unreadable body
     * would fail in the container and be retried, and a valid filing with a type header would end as
     * {@code analysis.failed}. The listener receives the raw message and {@code FilingSubmittedReader}
     * reads it.
     */
    @Bean
    RabbitTemplateCustomizer jsonTemplateConverter(final JsonMapper jsonMapper) {
        return template -> template.setMessageConverter(new JacksonJsonMessageConverter(jsonMapper));
    }

    /** An invalid message is never retried; the recoverer sends it to the dead-letter queue at once. */
    @Bean
    RabbitListenerRetrySettingsCustomizer skipRetryForInvalidMessages() {
        return settings -> settings.setExceptionPredicate(failure -> !InvalidFilingMessageException.isCauseOf(failure));
    }
}
