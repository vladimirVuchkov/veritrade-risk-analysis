package com.veritrade.ingestion.config;

import static com.veritrade.contracts.messaging.MessagingTopology.ARG_DEAD_LETTER_EXCHANGE;
import static com.veritrade.contracts.messaging.MessagingTopology.ARG_DEAD_LETTER_ROUTING_KEY;
import static com.veritrade.contracts.messaging.MessagingTopology.DEAD_LETTER_EXCHANGE;
import static com.veritrade.contracts.messaging.MessagingTopology.EVENTS_EXCHANGE;
import static com.veritrade.contracts.messaging.MessagingTopology.Q_INGESTION_ANALYSIS_EVENTS;
import static com.veritrade.contracts.messaging.MessagingTopology.RK_ANALYSIS_ALL;
import static com.veritrade.contracts.messaging.MessagingTopology.deadLetterQueue;

import com.veritrade.ingestion.messaging.InvalidEventException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** The topology Ingestion uses, exactly as in docs/contracts/messaging-topology.md. */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EVENTS_EXCHANGE).durable(true).build();
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    Queue analysisEventsQueue() {
        return QueueBuilder.durable(Q_INGESTION_ANALYSIS_EVENTS)
                .withArgument(ARG_DEAD_LETTER_EXCHANGE, DEAD_LETTER_EXCHANGE)
                .withArgument(ARG_DEAD_LETTER_ROUTING_KEY, Q_INGESTION_ANALYSIS_EVENTS)
                .build();
    }

    @Bean
    Queue analysisEventsDeadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueue(Q_INGESTION_ANALYSIS_EVENTS)).build();
    }

    @Bean
    Binding analysisEventsBinding(Queue analysisEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(analysisEventsQueue).to(eventsExchange).with(RK_ANALYSIS_ALL);
    }

    @Bean
    Binding analysisEventsDeadLetterBinding(Queue analysisEventsDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(analysisEventsDeadLetterQueue).to(deadLetterExchange).with(Q_INGESTION_ANALYSIS_EVENTS);
    }

    /**
     * The JSON converter goes on the template only. It is deliberately not a {@code MessageConverter}
     * bean: Spring Boot would also give it to the listener container, which would then convert the body
     * by the producer's {@code __TypeId__} header before the reader sees it. The listener receives the
     * raw message and {@code AnalysisEventReader} dispatches on {@code eventType}.
     */
    @Bean
    RabbitTemplateCustomizer jsonTemplateConverter(JsonMapper jsonMapper) {
        return template -> template.setMessageConverter(new JacksonJsonMessageConverter(jsonMapper));
    }

    /** After the last attempt the message is rejected without requeue, so it goes to the dead-letter queue. */
    @Bean
    MessageRecoverer messageRecoverer() {
        return new RejectAndDontRequeueRecoverer();
    }

    /** An unreadable or invalid event fails the same way on every attempt, so it is not retried. */
    @Bean
    RabbitListenerRetrySettingsCustomizer noRetryForInvalidEvents() {
        return settings -> settings.setExceptionPredicate(exception -> !InvalidEventException.isUnprocessable(exception));
    }
}
