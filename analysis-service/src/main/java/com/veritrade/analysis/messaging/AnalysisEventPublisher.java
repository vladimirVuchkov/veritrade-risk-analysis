package com.veritrade.analysis.messaging;

import com.veritrade.contracts.event.EventEnvelope;
import com.veritrade.contracts.messaging.MessagingTopology;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

/**
 * Publishes analysis events to {@code veritrade.events} with the AMQP properties from the contract
 * ({@code messageId} = eventId, JSON content type, correlation id, persistent) and waits for the
 * publisher confirm. A missing or negative confirm, or an unroutable return, throws, so the
 * listener retry treats it like any other processing failure.
 */
@Component
public class AnalysisEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MessageConverter messageConverter;
    private final Duration confirmTimeout;

    public AnalysisEventPublisher(
            RabbitTemplate rabbitTemplate, MessageConverter messageConverter, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.messageConverter = messageConverter;
        this.confirmTimeout = properties.confirmTimeout();
    }

    public void publish(EventEnvelope<?> event) {
        String eventId = event.eventId().toString();
        CorrelationData correlation = new CorrelationData(eventId);
        rabbitTemplate.send(MessagingTopology.EVENTS_EXCHANGE, event.eventType().routingKey(),
                toMessage(event), correlation);
        awaitConfirm(event, correlation);
    }

    private Message toMessage(EventEnvelope<?> event) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(event.eventId().toString());
        properties.setCorrelationId(event.correlationId());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return messageConverter.toMessage(event, properties);
    }

    private void awaitConfirm(EventEnvelope<?> event, CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw new EventPublishException(event, "negative confirm: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new EventPublishException(event, "unroutable, returned with "
                        + correlation.getReturned().getReplyText());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(event, "interrupted while waiting for the confirm", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishException(event, "no confirm within " + confirmTimeout, e);
        }
    }
}
